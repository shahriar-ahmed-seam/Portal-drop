use crate::{
    files::{BUFFER_SIZE, Complete, Files, preview_file, preview_text},
    model::{
        Chat, DeviceInfo, Item, Kind, Offset, PROTOCOL_VERSION, PairRequest, Receipt, StatusInfo,
        TransferCommand, TransferDirection, TransferResponse, TransferState, format_peer_label,
        load_notification_settings, local_name, local_os, now_ms, random_secret, valid_id,
    },
    network::HotspotDiscovery,
    sound::{self, SoundKind},
    store::{Incoming, Peer, Progress, Store, private_directory},
};
use anyhow::Result;
use axum::{
    Extension, Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, FromRequestParts, Path, Query, State},
    http::{
        HeaderMap, HeaderName, HeaderValue, StatusCode,
        header::{AUTHORIZATION, CACHE_CONTROL, CONTENT_LENGTH, CONTENT_TYPE},
        request::Parts,
    },
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use axum_server::{
    accept::{Accept, LocalAddr, LocalAddrAcceptor, LocalAddrService},
    tls_rustls::{RustlsAcceptor, RustlsConfig},
};
use futures_util::{StreamExt, stream};
use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap},
    fs::{self, OpenOptions},
    future::{Ready, ready},
    io::{self, BufRead, BufReader, ErrorKind, Read, Write},
    net::{IpAddr, SocketAddr, TcpListener, TcpStream},
    path::{Path as FsPath, PathBuf},
    process::Stdio,
    sync::{
        Arc, Mutex as StdMutex, Weak,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};
use subtle::ConstantTimeEq;
use tokio::{
    fs::{File, OpenOptions as TokioOpenOptions},
    io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt, BufWriter, SeekFrom},
    sync::{Mutex, broadcast, mpsc},
};
use tokio_util::io::ReaderStream;

const APPLICATION_JSON_MAX: usize = 256 * 1024;
const UPLOAD_IDLE_TIMEOUT: Duration = Duration::from_secs(30);
const CONNECT_SOUND_DEBOUNCE: Duration = Duration::from_secs(10);
const DISCONNECT_GRACE: Duration = Duration::from_secs(3);

static HEADER_OS: HeaderName = HeaderName::from_static("x-portal-drop-os");
static HEADER_NAME: HeaderName = HeaderName::from_static("x-portal-drop-name");
static HEADER_VERSION: HeaderName = HeaderName::from_static("x-portal-drop-version");

type PeerRoutes = Arc<StdMutex<HashMap<(Peer, IpAddr, i64), usize>>>;

#[derive(Clone)]
struct App {
    store: Store,
    files: Files,
    inbox_locks: Arc<StdMutex<HashMap<String, Weak<Mutex<()>>>>>,
    desktop_helper: Option<PathBuf>,
    events_tx: broadcast::Sender<&'static str>,
    connected_clients: Arc<AtomicUsize>,
    connected_routes: PeerRoutes,
    last_disconnect: Arc<StdMutex<Option<Instant>>>,
    local_os: &'static str,
    local_name: String,
}

impl App {
    fn item_lock(&self, peer: &Peer, id: &str) -> Arc<Mutex<()>> {
        let key = format!("{}:{id}", hex::encode(peer));
        let mut guard = self
            .inbox_locks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if guard.len() > 64 {
            guard.retain(|_, value| value.strong_count() > 0);
        }
        if let Some(existing) = guard.get(&key).and_then(Weak::upgrade) {
            return existing;
        }
        let fresh = Arc::new(Mutex::new(()));
        guard.insert(key, Arc::downgrade(&fresh));
        fresh
    }
}

#[derive(Deserialize, Serialize)]
struct WakeConfig {
    port: u16,
    token: String,
}

pub fn wake_daemon(data_dir: &FsPath) {
    if let Some((mut stream, config)) = connect_local_wake(data_dir) {
        let _ = stream.set_nodelay(true);
        let _ = stream.set_write_timeout(Some(Duration::from_millis(250)));
        let _ = writeln!(stream, "wake {}", config.token);
        let _ = stream.flush();
    }
}

fn connect_local_wake(data_dir: &FsPath) -> Option<(TcpStream, WakeConfig)> {
    let raw = fs::read_to_string(data_dir.join("wake.json")).ok()?;
    let config = serde_json::from_str::<WakeConfig>(&raw).ok()?;
    let addr = SocketAddr::from(([127, 0, 0, 1], config.port));
    let stream = TcpStream::connect_timeout(&addr, Duration::from_millis(250)).ok()?;
    Some((stream, config))
}

pub fn local_listen_address(data_dir: &FsPath) -> Option<SocketAddr> {
    local_wake_query(data_dir, "listen", 80)?
        .trim()
        .parse()
        .ok()
}

pub fn add_local_route_status(data_dir: &FsPath, status: &mut StatusInfo) {
    if !status.connected {
        return;
    }
    if let Some(routes) = local_wake_query(data_dir, "routes", 4096)
        .and_then(|result| serde_json::from_str(&result).ok())
    {
        status.connected_local_routes = routes;
        status.connected_local_ips = status.connected_local_routes.keys().copied().collect();
    } else {
        // The command line client may be updated before an older daemon is restarted.
        status.connected_local_ips = local_wake_query(data_dir, "addresses", 4096)
            .and_then(|result| serde_json::from_str(&result).ok())
            .unwrap_or_default();
    }
}

fn local_wake_query(data_dir: &FsPath, command: &str, limit: u64) -> Option<String> {
    let (mut stream, config) = connect_local_wake(data_dir)?;
    let timeout = Some(Duration::from_millis(250));
    stream.set_read_timeout(timeout).ok()?;
    stream.set_write_timeout(timeout).ok()?;
    writeln!(stream, "{command} {}", config.token).ok()?;
    let mut response = String::new();
    BufReader::new(stream)
        .take(limit)
        .read_line(&mut response)
        .ok()?;
    Some(response)
}

#[derive(Clone)]
struct TransferAcceptor;

impl<S> Accept<tokio::net::TcpStream, S> for TransferAcceptor {
    type Stream = tokio::net::TcpStream;
    type Service = LocalAddrService<S>;
    type Future = Ready<io::Result<(Self::Stream, Self::Service)>>;

    fn accept(&self, stream: Self::Stream, service: S) -> Self::Future {
        match stream.set_nodelay(true) {
            Ok(()) => LocalAddrAcceptor::new().accept(stream, service),
            Err(error) => ready(Err(error)),
        }
    }
}

pub async fn serve(store: Store, files: Files, listen: SocketAddr, discover: bool) -> Result<()> {
    let identity = store.identity()?;
    let _ = store.set_connected(false);
    let _ = store.purge_expired(&files);

    let tls = RustlsConfig::from_pem(identity.cert, identity.key).await?;
    let listener = TcpListener::bind(listen)?;
    let address = listener.local_addr()?;
    #[cfg(target_os = "linux")]
    crate::watchdog::start(address);

    let (events_tx, _) = broadcast::channel::<&'static str>(64);
    let wake_path = files.data_dir().join("wake.json");
    let connected_routes = PeerRoutes::default();
    let _wake_guard = start_local_wake_server(
        &wake_path,
        events_tx.clone(),
        address,
        connected_routes.clone(),
        store.clone(),
    );

    let app_state = App {
        store: store.clone(),
        files: files.clone(),
        inbox_locks: Arc::new(StdMutex::new(HashMap::new())),
        desktop_helper: desktop_helper(),
        events_tx: events_tx.clone(),
        connected_clients: Arc::new(AtomicUsize::new(0)),
        connected_routes: connected_routes.clone(),
        last_disconnect: Arc::new(StdMutex::new(None)),
        local_os: local_os(),
        local_name: local_name(),
    };

    // Watch durable queue changes independently of local wake notifications.
    let reaper_app = app_state.clone();
    tokio::spawn(async move {
        let mut last_revision = reaper_app.store.wake_revision().ok();
        let mut tick_count: u64 = 0;
        let mut interval = tokio::time::interval(Duration::from_secs(1));
        loop {
            interval.tick().await;
            tick_count = tick_count.wrapping_add(1);
            let reap = tick_count.is_multiple_of(5);
            let watch = reaper_app.connected_clients.load(Ordering::Relaxed) > 0
                || reaper_app.events_tx.receiver_count() > 0;
            if !reap && !watch {
                continue;
            }
            let store = reaper_app.store.clone();
            let files = reaper_app.files.clone();
            let Ok((purged, revision)) = tokio::task::spawn_blocking(move || {
                let purged = reap && store.purge_expired(&files).is_ok_and(|count| count > 0);
                let revision = if watch {
                    store.wake_revision().ok()
                } else {
                    None
                };
                (purged, revision)
            })
            .await
            else {
                continue;
            };
            if purged {
                let _ = reaper_app.events_tx.send("expire");
            }
            if let Some(current) = revision
                && last_revision
                    .replace(current)
                    .is_some_and(|last| current != last)
            {
                let _ = reaper_app.events_tx.send("sync");
            }
        }
    });

    if discover
        && let SocketAddr::V4(address) = address
        && !address.ip().is_loopback()
    {
        let store = store.clone();
        tokio::spawn(async move {
            let mut discovery = HotspotDiscovery::default();
            let mut interval = tokio::time::interval(Duration::from_secs(2));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                interval.tick().await;
                let store = store.clone();
                let routes = connected_routes.clone();
                let Ok(next) = tokio::task::spawn_blocking(move || {
                    let peer = store.active_peer().ok().flatten();
                    let connected = peer
                        .map(|peer| connected_ips(&routes, peer))
                        .unwrap_or_default();
                    discovery.retry(address, peer, &connected);
                    discovery
                })
                .await
                else {
                    break;
                };
                discovery = next;
            }
        });
    }

    let app = Router::new()
        .route("/v1/pair", post(pair))
        .route("/v1/info", get(info))
        .route("/v1/events", get(events))
        .route("/v1/chats", get(chats))
        .route("/v1/chats/{id}", put(write_chat))
        .route(
            "/v1/transfers/{direction}/{id}",
            get(transfer_info).post(control_transfer),
        )
        .route("/v1/outbox", get(outbox))
        .route("/v1/outbox/{id}/ack", post(ack))
        .route("/v1/outbox/{id}/file", get(download))
        .route("/v1/inbox", post(offer))
        .route("/v1/inbox/{id}/file", put(upload))
        .layer(DefaultBodyLimit::max(APPLICATION_JSON_MAX))
        .with_state(app_state.clone());

    let handle = axum_server::Handle::new();
    let acceptor = RustlsAcceptor::new(tls).acceptor(TransferAcceptor);
    let mut server = axum_server::from_tcp(listener)?
        .acceptor(acceptor)
        .handle(handle.clone());
    server
        .http_builder()
        .http2()
        .adaptive_window(true)
        .max_frame_size(64 * 1024);
    let server = server.serve(app.into_make_service());

    let _discovery = if discover && !address.ip().is_loopback() {
        match advertise(
            address,
            &identity.fingerprint,
            app_state.local_os,
            &app_state.local_name,
        ) {
            Ok(daemon) => Some(daemon),
            Err(error) => {
                eprintln!("LAN discovery unavailable: {error}");
                None
            }
        }
    } else {
        None
    };
    println!("Portal Drop listening on {address}");
    tokio::select! {
        result = server => result?,
        () = shutdown_signal() => {
            let _ = store.set_connected(false);
            handle.shutdown();
        }
    }
    Ok(())
}

struct WakeGuard {
    path: PathBuf,
    _shutdown: mpsc::Sender<()>,
}

impl Drop for WakeGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

fn start_local_wake_server(
    wake_path: &FsPath,
    events_tx: broadcast::Sender<&'static str>,
    transfer_address: SocketAddr,
    connected_routes: PeerRoutes,
    store: Store,
) -> Option<WakeGuard> {
    let listener = TcpListener::bind("127.0.0.1:0").ok()?;
    let port = listener.local_addr().ok()?.port();
    let token = random_secret().ok()?;
    let payload = serde_json::to_string(&WakeConfig {
        port,
        token: token.clone(),
    })
    .ok()?;
    if write_private_file(wake_path, payload.as_bytes()).is_err() {
        return None;
    }
    let (shutdown_tx, mut rx) = tokio::sync::mpsc::channel::<()>(1);
    std::thread::spawn(move || {
        let _ = listener.set_nonblocking(false);
        for incoming in listener.incoming() {
            if rx.try_recv().is_ok() || rx.is_closed() {
                break;
            }
            let Ok(stream) = incoming else {
                break;
            };
            let _ = stream.set_nodelay(true);
            let expected = token.clone();
            let tx = events_tx.clone();
            let connected_routes = connected_routes.clone();
            let store = store.clone();
            std::thread::spawn(move || {
                let _ = stream.set_read_timeout(Some(Duration::from_millis(500)));
                let mut reader = BufReader::new(&stream).take(160);
                let mut line = String::new();
                if reader.read_line(&mut line).is_err() {
                    return;
                }
                let trimmed = line.trim();
                if let Some(candidate) = trimmed.strip_prefix("wake ") {
                    if bool::from(candidate.as_bytes().ct_eq(expected.as_bytes())) {
                        let _ = tx.send("sync");
                    }
                } else if let Some(candidate) = trimmed.strip_prefix("listen ") {
                    if bool::from(candidate.as_bytes().ct_eq(expected.as_bytes())) {
                        let mut out = stream;
                        let _ = out.set_write_timeout(Some(Duration::from_millis(250)));
                        let _ = writeln!(out, "{transfer_address}");
                    }
                } else if let Some((command, candidate)) = trimmed.split_once(' ')
                    && matches!(command, "addresses" | "routes")
                {
                    if bool::from(candidate.as_bytes().ct_eq(expected.as_bytes())) {
                        let routes = store
                            .active_peer()
                            .ok()
                            .flatten()
                            .map(|peer| connected_route_times(&connected_routes, peer))
                            .unwrap_or_default();
                        let mut out = stream;
                        let _ = out.set_write_timeout(Some(Duration::from_millis(250)));
                        let encoded = if command == "addresses" {
                            serde_json::to_string(&routes.keys().collect::<Vec<_>>())
                        } else {
                            serde_json::to_string(&routes)
                        };
                        if let Ok(payload) = encoded {
                            let _ = writeln!(out, "{payload}");
                        }
                    }
                } else if let Some(candidate) = trimmed.strip_prefix("watch ") {
                    if !bool::from(candidate.as_bytes().ct_eq(expected.as_bytes())) {
                        return;
                    }
                    let _ = stream.set_read_timeout(None);
                    let mut out = stream;
                    let _ = out.set_write_timeout(Some(Duration::from_secs(2)));
                    let mut rx_ev = tx.subscribe();
                    if writeln!(out, "ready").is_err() {
                        return;
                    }
                    let mut idle_ticks = 0u32;
                    loop {
                        match rx_ev.try_recv() {
                            Ok(reason) => {
                                idle_ticks = 0;
                                if writeln!(out, "{reason}").is_err() {
                                    break;
                                }
                            }
                            Err(broadcast::error::TryRecvError::Lagged(_)) => {
                                idle_ticks = 0;
                                if writeln!(out, "sync").is_err() {
                                    break;
                                }
                            }
                            Err(broadcast::error::TryRecvError::Closed) => break,
                            Err(broadcast::error::TryRecvError::Empty) => {
                                std::thread::sleep(Duration::from_millis(25));
                                idle_ticks += 1;
                                if idle_ticks >= 600 {
                                    idle_ticks = 0;
                                    if writeln!(out, "ping").is_err() {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
    });
    Some(WakeGuard {
        path: wake_path.to_owned(),
        _shutdown: shutdown_tx,
    })
}

#[cfg(unix)]
fn write_private_file(path: &FsPath, bytes: &[u8]) -> std::io::Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
    file.write_all(bytes)
}

#[cfg(not(unix))]
fn write_private_file(path: &FsPath, bytes: &[u8]) -> std::io::Result<()> {
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(path)?;
    file.write_all(bytes)
}

#[cfg(unix)]
async fn shutdown_signal() {
    match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
        Ok(mut terminate) => {
            tokio::select! {
                _ = terminate.recv() => {}
                _ = tokio::signal::ctrl_c() => {}
            }
        }
        Err(_) => {
            let _ = tokio::signal::ctrl_c().await;
        }
    }
}

#[cfg(not(unix))]
async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
}

fn advertise(
    address: SocketAddr,
    fingerprint: &str,
    os: &str,
    name: &str,
) -> Result<ServiceDaemon> {
    let daemon = ServiceDaemon::new()?;
    let label = &fingerprint[..12];
    let host = format!("portaldrop-{label}.local.");
    let instance = format!("Portal Drop {label}");
    let ip = address.ip();
    let addresses = [ip];
    let advertised = if ip.is_unspecified() {
        &[][..]
    } else {
        &addresses[..]
    };
    let mut service = ServiceInfo::new(
        "_portaldrop._tcp.local.",
        &instance,
        &host,
        advertised,
        address.port(),
        &[("fp", fingerprint), ("os", os), ("name", name)][..],
    )?;
    if ip.is_unspecified() {
        daemon.disable_interface(if ip.is_ipv4() {
            IfKind::IPv6
        } else {
            IfKind::IPv4
        })?;
        service = service.enable_addr_auto();
    }
    daemon.register(service)?;
    Ok(daemon)
}

fn device_headers(app: &App) -> HeaderMap {
    let mut headers = HeaderMap::new();
    if let Ok(value) = HeaderValue::from_str(app.local_os) {
        headers.insert(HEADER_OS.clone(), value);
    }
    if let Ok(value) = HeaderValue::from_str(&app.local_name) {
        headers.insert(HEADER_NAME.clone(), value);
    }
    headers.insert(HEADER_VERSION.clone(), HeaderValue::from(PROTOCOL_VERSION));
    headers
}

fn protocol_version(headers: &HeaderMap) -> u32 {
    headers
        .get(&HEADER_VERSION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u32>().ok())
        .unwrap_or(1)
}

fn record_peer_headers(app: &App, peer: &Peer, headers: &HeaderMap) {
    let peer_os = headers.get(&HEADER_OS).and_then(|v| v.to_str().ok());
    let peer_name = headers.get(&HEADER_NAME).and_then(|v| v.to_str().ok());
    if peer_os.is_some() || peer_name.is_some() {
        let _ = app.store.update_peer_meta(peer, peer_os, peer_name);
    }
}

#[derive(Debug)]
enum ApiError {
    Client(StatusCode, &'static str),
    Server(anyhow::Error),
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        match self {
            Self::Client(status, message) => (status, message).into_response(),
            Self::Server(error) => {
                eprintln!("Portal Drop request failed: {error:#}");
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "could not save or read item",
                )
                    .into_response()
            }
        }
    }
}

impl<E> From<E> for ApiError
where
    E: Into<anyhow::Error>,
{
    fn from(error: E) -> Self {
        Self::Server(error.into())
    }
}

type ApiResult<T> = std::result::Result<T, ApiError>;
const AUTH_ERROR: ApiError = ApiError::Client(StatusCode::UNAUTHORIZED, "pair the device first");
const MISSING_ITEM: ApiError = ApiError::Client(StatusCode::NOT_FOUND, "item not found");
const OFFSET_ERROR: ApiError = ApiError::Client(StatusCode::CONFLICT, "check the current offset");
const HASH_ERROR: ApiError = ApiError::Client(
    StatusCode::UNPROCESSABLE_ENTITY,
    "invalid payload or checksum; restart transfer",
);

const PAUSED_TRANSFER: ApiError = ApiError::Client(StatusCode::LOCKED, "transfer paused");
const CANCELED_TRANSFER: ApiError = ApiError::Client(StatusCode::GONE, "transfer canceled");
const FAILED_TRANSFER: ApiError = ApiError::Client(
    StatusCode::FAILED_DEPENDENCY,
    "transfer failed; retry when ready",
);

fn check_transfer_state(state: TransferState) -> ApiResult<()> {
    match state {
        TransferState::Queued => Ok(()),
        TransferState::Paused => Err(PAUSED_TRANSFER),
        TransferState::Canceled => Err(CANCELED_TRANSFER),
        TransferState::Failed => Err(FAILED_TRANSFER),
        TransferState::Delivered => Err(MISSING_ITEM),
    }
}

struct Authorized(Peer);

impl FromRequestParts<App> for Authorized {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, app: &App) -> ApiResult<Self> {
        let token = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|header| header.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "))
            .ok_or(AUTH_ERROR)?;
        let peer = app.store.authenticate(token)?.ok_or(AUTH_ERROR)?;
        record_peer_headers(app, &peer, &parts.headers);
        Ok(Authorized(peer))
    }
}

async fn pair(State(app): State<App>, Json(pair): Json<PairRequest>) -> ApiResult<Response> {
    if !app.store.pair(
        &pair.code,
        &pair.token,
        pair.device_os.as_deref(),
        pair.device_name.as_deref(),
    )? {
        return Err(ApiError::Client(
            StatusCode::FORBIDDEN,
            "invite expired, invalid, or already used",
        ));
    }
    let _ = app.events_tx.send("pair");
    Ok((StatusCode::NO_CONTENT, device_headers(&app)).into_response())
}

async fn info(State(app): State<App>, Authorized(peer): Authorized) -> ApiResult<Response> {
    let _ = app.store.touch_last_seen(&peer);
    let body = DeviceInfo {
        os: app.local_os.to_owned(),
        name: app.local_name.clone(),
        protocol_version: PROTOCOL_VERSION,
    };
    Ok((device_headers(&app), Json(body)).into_response())
}

struct PresenceGuard {
    app: App,
    route: (Peer, IpAddr, i64),
}

fn connected_ips(routes: &PeerRoutes, peer: Peer) -> Vec<IpAddr> {
    connected_route_times(routes, peer).into_keys().collect()
}

fn connected_route_times(routes: &PeerRoutes, peer: Peer) -> BTreeMap<IpAddr, i64> {
    let mut times = BTreeMap::new();
    for (_, ip, started_at) in routes
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .keys()
        .filter(|(trusted, _, _)| *trusted == peer)
    {
        let latest = times.entry(*ip).or_insert(*started_at);
        *latest = (*latest).max(*started_at);
    }
    times
}

impl PresenceGuard {
    fn enter(app: App, peer: Peer, local_ip: IpAddr, started_at: i64) -> Self {
        let route = (peer, local_ip, started_at);
        *app.connected_routes
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .entry(route)
            .or_default() += 1;
        let previous = app.connected_clients.fetch_add(1, Ordering::SeqCst);
        let _ = app.store.set_connected(true);
        let _ = app.events_tx.send("presence");
        if previous == 0 {
            let should_notify = {
                let guard = app
                    .last_disconnect
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                match *guard {
                    None => true,
                    Some(when) => when.elapsed() >= CONNECT_SOUND_DEBOUNCE,
                }
            };
            if should_notify {
                notify_connected(&app);
            }
        }
        Self { app, route }
    }
}

impl Drop for PresenceGuard {
    fn drop(&mut self) {
        {
            let mut routes = self
                .app
                .connected_routes
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            let count = routes
                .get_mut(&self.route)
                .expect("presence route is registered");
            *count -= 1;
            if *count == 0 {
                routes.remove(&self.route);
            }
        }
        let _ = self.app.events_tx.send("presence");
        let previous = self.app.connected_clients.fetch_sub(1, Ordering::SeqCst);
        if previous <= 1 {
            {
                let mut guard = self
                    .app
                    .last_disconnect
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                *guard = Some(Instant::now());
            }
            let app = self.app.clone();
            tokio::spawn(async move {
                tokio::time::sleep(DISCONNECT_GRACE).await;
                if app.connected_clients.load(Ordering::SeqCst) == 0 {
                    let _ = app.store.set_connected(false);
                    let _ = app.events_tx.send("presence");
                }
            });
        }
    }
}

async fn events(
    State(app): State<App>,
    Authorized(peer): Authorized,
    Extension(LocalAddr(address)): Extension<LocalAddr>,
) -> ApiResult<Response> {
    let started_at = now_ms();
    let (tx, rx) = mpsc::channel::<std::io::Result<String>>(16);
    let mut events_rx = app.events_tx.subscribe();
    let producer_app = app.clone();
    tokio::spawn(async move {
        let _guard = PresenceGuard::enter(producer_app.clone(), peer, address.ip(), started_at);
        let ready = serde_json::to_string(&DeviceInfo {
            os: producer_app.local_os.to_owned(),
            name: producer_app.local_name.clone(),
            protocol_version: PROTOCOL_VERSION,
        })
        .unwrap_or_else(|_| "{}".to_owned());
        if tx
            .send(Ok(format!("event: ready\ndata: {ready}\n\n")))
            .await
            .is_err()
        {
            return;
        }
        let mut keepalive = tokio::time::interval(Duration::from_secs(20));
        keepalive.tick().await; // skip immediate tick
        loop {
            tokio::select! {
                () = tx.closed() => break,
                _ = keepalive.tick() => {
                    let _ = producer_app.store.touch_last_seen(&peer);
                    if tx.send(Ok(": keepalive\n\n".to_owned())).await.is_err() {
                        break;
                    }
                }
                result = events_rx.recv() => {
                    match result {
                        Ok(reason) => {
                            let frame = format!("event: sync\ndata: {{\"reason\":\"{reason}\"}}\n\n");
                            if tx.send(Ok(frame)).await.is_err() {
                                break;
                            }
                        }
                        Err(broadcast::error::RecvError::Lagged(_)) => {
                            if tx.send(Ok("event: sync\ndata: {\"reason\":\"sync\"}\n\n".to_owned())).await.is_err() {
                                break;
                            }
                        }
                        Err(broadcast::error::RecvError::Closed) => break,
                    }
                }
            }
        }
    });

    let body_stream = stream::unfold(rx, |mut rx| async move {
        rx.recv().await.map(|chunk| (chunk, rx))
    });
    let mut headers = device_headers(&app);
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("text/event-stream"));
    headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    headers.insert(
        HeaderName::from_static("x-accel-buffering"),
        HeaderValue::from_static("no"),
    );
    Ok((headers, Body::from_stream(body_stream)).into_response())
}

async fn chats(State(app): State<App>, _: Authorized, headers: HeaderMap) -> ApiResult<Response> {
    let include_deleted = protocol_version(&headers) >= 3;
    Ok((
        device_headers(&app),
        Json(app.store.chats(include_deleted)?),
    )
        .into_response())
}

async fn write_chat(
    State(app): State<App>,
    Authorized(peer): Authorized,
    Path(id): Path<String>,
    Json(chat): Json<Chat>,
) -> ApiResult<StatusCode> {
    if chat.id != id || chat.validate().is_err() {
        return Err(ApiError::Client(
            StatusCode::BAD_REQUEST,
            "invalid chat metadata",
        ));
    }
    if !app.store.write_chat(Some(&peer), &chat, Some(&app.files))? {
        return Err(AUTH_ERROR);
    }
    let _ = app.events_tx.send("chats");
    Ok(StatusCode::NO_CONTENT)
}

async fn outbox(
    State(app): State<App>,
    Authorized(peer): Authorized,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let version = protocol_version(&headers);
    let _ = app.store.touch_last_seen(&peer);
    Ok((
        device_headers(&app),
        Json(app.store.outbox(&peer, version)?),
    )
        .into_response())
}

fn transfer_address(direction: &str, id: &str) -> ApiResult<TransferDirection> {
    if !valid_id(id) {
        return Err(MISSING_ITEM);
    }
    TransferDirection::parse(direction).ok_or(MISSING_ITEM)
}

async fn transfer_info(
    State(app): State<App>,
    Authorized(peer): Authorized,
    Path((direction, id)): Path<(String, String)>,
) -> ApiResult<Json<TransferResponse>> {
    let direction = transfer_address(&direction, &id)?;
    let transfer = app
        .store
        .transfer(&peer, direction, &id)?
        .ok_or(MISSING_ITEM)?;
    Ok(Json(TransferResponse {
        state: transfer.state,
    }))
}

async fn control_transfer(
    State(app): State<App>,
    Authorized(peer): Authorized,
    Path((direction, id)): Path<(String, String)>,
    Json(command): Json<TransferCommand>,
) -> ApiResult<Json<TransferResponse>> {
    let direction = transfer_address(&direction, &id)?;
    let store = app.store.clone();
    let transfer = tokio::task::spawn_blocking(move || {
        store.control_transfer(&peer, direction, &id, command.action)
    })
    .await??
    .ok_or(MISSING_ITEM)?;
    if transfer.state == TransferState::Canceled {
        app.files.discard_transfer(&peer, direction, &transfer.item);
    }
    let _ = app.events_tx.send("transfer");
    Ok(Json(TransferResponse {
        state: transfer.state,
    }))
}

async fn ack(
    State(app): State<App>,
    Authorized(peer): Authorized,
    Path(id): Path<String>,
) -> ApiResult<StatusCode> {
    if !valid_id(&id) {
        return Err(MISSING_ITEM);
    }
    if !app.store.acknowledge(&peer, &id)? {
        if let Some(transfer) = app.store.transfer(&peer, TransferDirection::Sent, &id)? {
            check_transfer_state(transfer.state)?;
        }
        return Err(MISSING_ITEM);
    }
    app.store.clear_progress(&id, "sent")?;
    if let Err(error) = tokio::fs::remove_file(app.files.outgoing(&id)).await
        && error.kind() != ErrorKind::NotFound
    {
        eprintln!("Failed to remove delivered file from outgoing queue: {error}");
    }
    let _ = tokio::fs::remove_file(app.files.outgoing_ref(&id)).await;
    let _ = app.events_tx.send("ack");
    Ok(StatusCode::NO_CONTENT)
}

async fn download(
    State(app): State<App>,
    Authorized(peer): Authorized,
    Path(id): Path<String>,
    Query(Offset { offset }): Query<Offset>,
) -> ApiResult<Response> {
    if !valid_id(&id) {
        return Err(MISSING_ITEM);
    }
    let direction = TransferDirection::Sent;
    let transfer = app
        .store
        .transfer(&peer, direction, &id)?
        .ok_or(MISSING_ITEM)?;
    check_transfer_state(transfer.state)?;
    let item = transfer.item;
    let (size, _) = item.payload().ok_or(MISSING_ITEM)?;
    let remaining = size.checked_sub(offset).ok_or(OFFSET_ERROR)?;

    let mut file = match File::open(app.files.outgoing_payload(&item)).await {
        Ok(file) if file.metadata().await?.len() == size => file,
        _ => {
            app.store.fail_transfer(&peer, direction, &id)?;
            let _ = app.events_tx.send("transfer");
            return Err(FAILED_TRANSFER);
        }
    };
    file.seek(SeekFrom::Start(offset)).await?;
    let gate = TransferGate::new(app.store.clone(), peer, direction, id);
    let progress = ProgressWatch::new(app.store, app.events_tx, &item, "sent", offset, size);
    let reader = ReaderStream::with_capacity(file.take(remaining), BUFFER_SIZE);
    let stream = stream::unfold(Some((reader, progress, gate)), |state| async move {
        let (mut reader, mut progress, mut gate) = state?;
        if gate.check(false).await.is_err() {
            let error = std::io::Error::new(ErrorKind::Interrupted, "transfer stopped");
            return Some((Err(error), None));
        }
        let chunk = reader.next().await?;
        if let Ok(bytes) = &chunk {
            progress.advance(bytes.len() as u64);
        }
        Some((chunk, Some((reader, progress, gate))))
    });
    let body = Body::from_stream(stream);
    Ok((
        [
            (CONTENT_TYPE, "application/octet-stream".to_owned()),
            (CONTENT_LENGTH, remaining.to_string()),
        ],
        body,
    )
        .into_response())
}

async fn offer(
    State(app): State<App>,
    Authorized(peer): Authorized,
    headers: HeaderMap,
    Json(mut item): Json<Item>,
) -> ApiResult<Json<Receipt>> {
    if item.validate().is_err() {
        return Err(ApiError::Client(
            StatusCode::BAD_REQUEST,
            "invalid item metadata",
        ));
    }
    let item_lock = app.item_lock(&peer, &item.id);
    let _guard = item_lock.lock().await;
    if let Some(incoming) = app.store.incoming(&peer, &item.id)? {
        if incoming.item.chat_id() == item.chat_id() {
            item.chat_id.clone_from(&incoming.item.chat_id);
        }
        // If an expired temporary item was scrubbed, still acknowledge it as received.
        let scrubbed_tombstone = incoming.received
            && incoming.item.text.is_none()
            && incoming.item.name.is_none()
            && incoming.item.payload().is_none();
        if !scrubbed_tombstone && incoming.item != item {
            return Err(ApiError::Client(
                StatusCode::CONFLICT,
                "item ID already used",
            ));
        }
        let size = item.payload().map_or(0, |(size, _)| size);
        if incoming.received {
            return Ok(Json(Receipt {
                received: true,
                offset: size,
            }));
        }

        if let Some(transfer) = app
            .store
            .transfer(&peer, TransferDirection::Received, &item.id)?
        {
            check_transfer_state(transfer.state)?;
        }
        let partial = app.files.partial(&peer, &item);
        return match tokio::fs::metadata(&partial).await {
            Ok(metadata) if metadata.len() == size => {
                let received =
                    complete(&app, &peer, &incoming, protocol_version(&headers) >= 4).await?;
                Ok(Json(Receipt {
                    received,
                    offset: if received { size } else { 0 },
                }))
            }
            Ok(metadata) if metadata.len() < size => Ok(Json(Receipt {
                received: false,
                offset: metadata.len(),
            })),
            Ok(_) => {
                tokio::fs::remove_file(partial).await?;
                Ok(Json(Receipt {
                    received: false,
                    offset: 0,
                }))
            }
            Err(error) if error.kind() == ErrorKind::NotFound => Ok(Json(Receipt {
                received: false,
                offset: 0,
            })),
            Err(error) => Err(error.into()),
        };
    }

    let saved_name = match item.kind {
        Kind::File => Some(app.files.initial_name(&app.store, &item)?),
        Kind::Text => None,
    };
    if !app.store.offer(&peer, &item, saved_name.as_deref())? {
        return Err(AUTH_ERROR);
    }
    let _ = app.events_tx.send("inbox");
    if let Some(text) = &item.text {
        notify_message(&app, &item, preview_text(text, 240).0);
    }
    Ok(Json(Receipt {
        received: item.text.is_some(),
        offset: 0,
    }))
}

async fn upload(
    State(app): State<App>,
    Authorized(peer): Authorized,
    Path(id): Path<String>,
    Query(Offset { offset }): Query<Offset>,
    headers: HeaderMap,
    body: Body,
) -> ApiResult<StatusCode> {
    if !valid_id(&id) {
        return Err(MISSING_ITEM);
    }
    let item_lock = app.item_lock(&peer, &id);
    let _guard = item_lock.lock().await;
    let incoming = app.store.incoming(&peer, &id)?.ok_or(MISSING_ITEM)?;
    let (size, _) = incoming.item.payload().ok_or(MISSING_ITEM)?;
    if incoming.received {
        return Ok(StatusCode::NO_CONTENT);
    }
    let direction = TransferDirection::Received;
    let transfer = app
        .store
        .transfer(&peer, direction, &id)?
        .ok_or(MISSING_ITEM)?;
    check_transfer_state(transfer.state)?;
    let remaining = size.checked_sub(offset).ok_or(OFFSET_ERROR)?;
    let length = headers
        .get(CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .ok_or(ApiError::Client(
            StatusCode::LENGTH_REQUIRED,
            "Content-Length is required",
        ))?;
    if length != remaining {
        return Err(ApiError::Client(
            StatusCode::BAD_REQUEST,
            "unexpected Content-Length",
        ));
    }

    let path = app.files.partial(&peer, &incoming.item);
    private_directory(path.parent().expect("partial folder"))?;
    let file = open_private_append(&path).await?;
    if file.metadata().await?.len() != offset {
        return Err(OFFSET_ERROR);
    }

    let mut progress = ProgressWatch::new(
        app.store.clone(),
        app.events_tx.clone(),
        &incoming.item,
        "received",
        offset,
        size,
    );
    let mut writer = BufWriter::with_capacity(BUFFER_SIZE, file);
    let mut gate = TransferGate::new(app.store.clone(), peer, direction, id);
    let transferred = append_upload(&mut writer, body, length, &mut progress, &mut gate).await;
    writer.flush().await?;
    let file = writer.into_inner();
    file.sync_all().await?;
    progress.save();
    drop(file);
    if matches!(transferred, Err(ApiError::Client(StatusCode::GONE, _))) {
        app.files.discard_transfer(&peer, direction, &incoming.item);
    }
    transferred?;
    gate.check(true).await?;

    if complete(&app, &peer, &incoming, protocol_version(&headers) >= 4).await? {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(HASH_ERROR)
    }
}

#[cfg(unix)]
async fn open_private_append(path: &FsPath) -> std::io::Result<File> {
    TokioOpenOptions::new()
        .append(true)
        .create(true)
        .mode(0o600)
        .open(path)
        .await
}

#[cfg(not(unix))]
async fn open_private_append(path: &FsPath) -> std::io::Result<File> {
    TokioOpenOptions::new()
        .append(true)
        .create(true)
        .open(path)
        .await
}

async fn complete(
    app: &App,
    peer: &Peer,
    incoming: &Incoming,
    manage_failures: bool,
) -> ApiResult<bool> {
    let item = &incoming.item;
    let result = app.files.finish(&app.store, peer, incoming).await?;
    let _ = app.store.clear_progress(&item.id, "received");
    let _ = app.events_tx.send("inbox");
    match result {
        Complete::Saved(Some(name)) => {
            let full_path = app.files.received_file(&name);
            let display = FsPath::new(&name)
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or(&name);
            notify_file(app, item, display, &full_path);
            Ok(true)
        }
        Complete::Saved(None) => {
            let path = app.files.received_text(peer, &item.id);
            let text = preview_file(&path, 240)
                .map_or_else(|_| "New message".to_owned(), |(text, _)| text);
            notify_message(app, item, &text);
            Ok(true)
        }
        Complete::InvalidPayload => {
            if manage_failures {
                app.store
                    .fail_transfer(peer, TransferDirection::Received, &item.id)?;
                let _ = app.events_tx.send("transfer");
            }
            Ok(false)
        }
        Complete::Revoked => {
            if let Some(transfer) =
                app.store
                    .transfer(peer, TransferDirection::Received, &item.id)?
            {
                check_transfer_state(transfer.state)?;
            }
            Err(AUTH_ERROR)
        }
    }
}

async fn append_upload(
    file: &mut BufWriter<File>,
    body: Body,
    bytes: u64,
    progress: &mut ProgressWatch,
    gate: &mut TransferGate,
) -> ApiResult<()> {
    let mut remaining = bytes;
    let mut stream = body.into_data_stream();
    let mut last_flush = Instant::now();
    let mut last_data = last_flush;
    loop {
        gate.check(false).await?;
        let next = match tokio::time::timeout(Duration::from_millis(200), stream.next()).await {
            Ok(item) => item,
            Err(_) => {
                file.flush().await?;
                last_flush = Instant::now();
                if last_data.elapsed() >= UPLOAD_IDLE_TIMEOUT {
                    return Err(ApiError::Client(
                        StatusCode::REQUEST_TIMEOUT,
                        "upload paused too long",
                    ));
                }
                continue;
            }
        };
        last_data = Instant::now();
        let Some(chunk) = next else { break };
        let chunk =
            chunk.map_err(|_| ApiError::Client(StatusCode::BAD_REQUEST, "upload interrupted"))?;
        remaining = remaining
            .checked_sub(chunk.len() as u64)
            .ok_or(ApiError::Client(
                StatusCode::BAD_REQUEST,
                "upload exceeded its size",
            ))?;
        file.write_all(&chunk).await?;
        progress.advance(chunk.len() as u64);
        if last_flush.elapsed() >= Duration::from_millis(350) {
            file.flush().await?;
            last_flush = Instant::now();
        }
    }
    if remaining != 0 {
        return Err(ApiError::Client(
            StatusCode::BAD_REQUEST,
            "upload interrupted",
        ));
    }
    Ok(())
}

struct TransferGate {
    store: Store,
    peer: Peer,
    direction: TransferDirection,
    id: String,
    last_check: Instant,
}

impl TransferGate {
    fn new(store: Store, peer: Peer, direction: TransferDirection, id: String) -> Self {
        Self {
            store,
            peer,
            direction,
            id,
            last_check: Instant::now(),
        }
    }

    async fn check(&mut self, force: bool) -> ApiResult<()> {
        if !force && self.last_check.elapsed() < Duration::from_millis(180) {
            return Ok(());
        }
        let store = self.store.clone();
        let peer = self.peer;
        let direction = self.direction;
        let id = self.id.clone();
        let transfer = tokio::task::spawn_blocking(move || store.transfer(&peer, direction, &id))
            .await??
            .ok_or(MISSING_ITEM)?;
        self.last_check = Instant::now();
        check_transfer_state(transfer.state)
    }
}

struct ProgressWatch {
    store: Store,
    events_tx: broadcast::Sender<&'static str>,
    current: Progress,
    last_write: Instant,
}

impl ProgressWatch {
    fn new(
        store: Store,
        events_tx: broadcast::Sender<&'static str>,
        item: &Item,
        direction: &str,
        offset: u64,
        total: u64,
    ) -> Self {
        let now = now_ms();
        let mut watch = Self {
            store,
            events_tx,
            current: Progress {
                id: item.id.clone(),
                chat_id: item.chat_id().to_owned(),
                kind: item.kind,
                name: item.name.clone(),
                direction: direction.to_owned(),
                offset,
                transferred: offset,
                total,
                started_at: now,
                updated_at: now,
                transfer_state: None,
            },
            last_write: Instant::now(),
        };
        watch.save();
        watch
    }

    fn advance(&mut self, size: u64) {
        self.current.transferred += size;
        if self.current.transferred == self.current.total
            || self.last_write.elapsed() >= Duration::from_millis(350)
        {
            self.save();
        }
    }

    fn save(&mut self) {
        self.current.updated_at = now_ms();
        let _ = self.store.set_progress(&self.current);
        let _ = self.events_tx.send("progress");
        self.last_write = Instant::now();
    }
}

fn notify_message(app: &App, item: &Item, text: &str) {
    let prefs = load_notification_settings(app.files.data_dir());
    if prefs.message_sound {
        sound::play(app.files.data_dir(), SoundKind::Message);
    }
    if !prefs.message_notify {
        return;
    }
    let helper = app.desktop_helper.clone();
    let files = app.files.clone();
    let chat = item.chat_id().to_owned();
    let reply_to = item.id.clone();
    let text = text.to_owned();
    let title = peer_title(&app.store, "New message");
    tokio::spawn(async move {
        let receive_dir = files.receive_dir();
        if let Some(helper) = helper
            && run_helper_stdin(
                &helper,
                &["--notify-chat", &chat],
                &[
                    ("PORTAL_DROP_DATA_DIR", files.data_dir().as_os_str()),
                    ("PORTAL_DROP_RECEIVE_DIR", receive_dir.as_os_str()),
                    ("PORTAL_DROP_REPLY_TO", reply_to.as_ref()),
                ],
                &text,
            )
            .await
        {
            return;
        }
        notify_fallback(&title, &text).await;
    });
}

fn notify_file(app: &App, item: &Item, name: &str, full_path: &FsPath) {
    let prefs = load_notification_settings(app.files.data_dir());
    if prefs.file_sound {
        sound::play(app.files.data_dir(), SoundKind::File);
    }
    if !prefs.file_notify {
        return;
    }
    let helper = app.desktop_helper.clone();
    let files = app.files.clone();
    let chat = item.chat_id().to_owned();
    let item_id = item.id.clone();
    let name = name.to_owned();
    let full_path = full_path.to_owned();
    tokio::spawn(async move {
        let receive_dir = files.receive_dir();
        if let Some(helper) = helper
            && helper_supports(&helper, "--notify-file")
            && run_helper_stdin(
                &helper,
                &["--notify-file", &chat],
                &[
                    ("PORTAL_DROP_DATA_DIR", files.data_dir().as_os_str()),
                    ("PORTAL_DROP_RECEIVE_DIR", receive_dir.as_os_str()),
                    ("PORTAL_DROP_ITEM_ID", item_id.as_ref()),
                    ("PORTAL_DROP_FILE_PATH", full_path.as_os_str()),
                ],
                &name,
            )
            .await
        {
            return;
        }
        notify_fallback("File received", &name).await;
    });
}

fn notify_connected(app: &App) {
    let prefs = load_notification_settings(app.files.data_dir());
    if prefs.connected_sound {
        sound::play(app.files.data_dir(), SoundKind::Connected);
    }
    if !prefs.connected_notify {
        return;
    }
    let helper = app.desktop_helper.clone();
    let label = peer_label(&app.store);
    let body = format!("{label} is now connected");
    tokio::spawn(async move {
        if let Some(helper) = helper
            && helper_supports(&helper, "--notify-connected")
            && run_helper_stdin(&helper, &["--notify-connected", &label], &[], &body).await
        {
            return;
        }
        notify_fallback("Device connected", &body).await;
    });
}

fn peer_label(store: &Store) -> String {
    match store.peer_identity() {
        Ok((os, name)) => format_peer_label(os.as_deref(), name.as_deref()),
        Err(_) => "Android".to_owned(),
    }
}

fn peer_title(store: &Store, fallback: &str) -> String {
    match store.peer_identity() {
        Ok((os, name)) if os.is_some() || name.is_some() => {
            format!(
                "{} · {fallback}",
                format_peer_label(os.as_deref(), name.as_deref())
            )
        }
        _ => fallback.to_owned(),
    }
}

async fn run_helper_stdin(
    helper: &FsPath,
    args: &[&str],
    envs: &[(&str, &std::ffi::OsStr)],
    stdin_text: &str,
) -> bool {
    let mut command = tokio::process::Command::new(helper);
    command.args(args).stdin(Stdio::piped());
    for (key, value) in envs {
        command.env(key, value);
    }
    let Ok(mut child) = command.spawn() else {
        return false;
    };
    let written = if let Some(mut input) = child.stdin.take() {
        input.write_all(stdin_text.as_bytes()).await.is_ok()
    } else {
        false
    };
    let success = child.wait().await.is_ok_and(|status| status.success());
    written && success
}

fn helper_supports(helper: &FsPath, flag: &str) -> bool {
    if cfg!(windows) {
        return true;
    }
    fs::read_to_string(helper)
        .map(|source| source.contains(flag))
        .unwrap_or(false)
}

fn desktop_helper() -> Option<PathBuf> {
    #[cfg(unix)]
    {
        let mut candidates = Vec::new();
        if let Ok(exe) = std::env::current_exe()
            && let Some(dir) = exe.parent()
        {
            candidates.push(dir.join("portal-drop-desktop"));
        }
        if let Some(home) = dirs::home_dir() {
            candidates.push(home.join(".local/bin/portal-drop-desktop"));
        }
        candidates.push(PathBuf::from("/usr/bin/portal-drop-desktop"));
        for helper in candidates {
            if let Ok(source) = fs::read_to_string(&helper)
                && source.contains("--notify-chat")
            {
                return Some(helper);
            }
        }
        None
    }
    #[cfg(windows)]
    {
        let exe = std::env::current_exe().ok()?;
        let helper = exe.parent()?.join("portal-drop-desktop.exe");
        helper.is_file().then_some(helper)
    }
}

#[cfg(unix)]
async fn notify_fallback(title: &str, text: &str) {
    let escaped = text
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;");
    let _ = tokio::process::Command::new("notify-send")
        .args([
            "--app-name=Portal Drop",
            "--icon=app.portaldrop",
            "--hint=string:desktop-entry:app.portaldrop",
            "--",
            title,
            &escaped,
        ])
        .status()
        .await;
}

#[cfg(windows)]
async fn notify_fallback(title: &str, text: &str) {
    let esc = |value: &str| {
        value
            .replace('&', "&amp;")
            .replace('<', "&lt;")
            .replace('>', "&gt;")
            .replace('"', "&quot;")
            .replace('\'', "''")
    };
    let script = format!(
        "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null; \
         [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > $null; \
         $xml = New-Object Windows.Data.Xml.Dom.XmlDocument; \
         $xml.LoadXml('<toast><visual><binding template=\"ToastGeneric\"><text>{}</text><text>{}</text></binding></visual><audio silent=\"true\"/></toast>'); \
         $toast = [Windows.UI.Notifications.ToastNotification]::new($xml); \
         [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Portal Drop').Show($toast);",
        esc(title),
        esc(text),
    );
    let _ = tokio::process::Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-Command",
            &script,
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
}
