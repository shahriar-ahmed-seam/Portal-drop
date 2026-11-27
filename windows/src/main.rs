#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    env, fs,
    io::{BufRead, BufReader, Read, Seek, SeekFrom, Write},
    net::TcpStream,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    thread,
    time::Duration,
};
use tao::{
    dpi::LogicalSize,
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy},
    window::{Icon, WindowBuilder},
};
use wry::{DragDropEvent, WebViewBuilder, http};

#[cfg(windows)]
mod tray;
#[cfg(windows)]
mod wifi;

const UI_HTML: &str = include_str!("../../desktop-ui/index.html");
const ICON_RGBA: &[u8] = include_bytes!("../assets/icon-64.rgba");

fn serve_media(cli: &Path, request: http::Request<Vec<u8>>) -> http::Response<Vec<u8>> {
    use http::{StatusCode, header};

    let failure = |status| {
        http::Response::builder()
            .status(status)
            .body(Vec::new())
            .unwrap()
    };
    let id = request.uri().path().trim_start_matches('/');
    if request.method() != http::Method::GET || id.len() > 36 {
        return failure(StatusCode::NOT_FOUND);
    }
    if request
        .headers()
        .get(header::ORIGIN)
        .is_some_and(|origin| origin != "null")
    {
        return failure(StatusCode::FORBIDDEN);
    }
    let Ok(details) = run_cli(cli, &["media-path".into(), id.into()], None).and_then(|source| {
        serde_json::from_str::<Value>(&source).map_err(|error| error.to_string())
    }) else {
        return failure(StatusCode::NOT_FOUND);
    };
    let (Some(path), Some(mime)) = (details["path"].as_str(), details["mime"].as_str()) else {
        return failure(StatusCode::NOT_FOUND);
    };
    let Ok(mut file) = fs::File::open(path) else {
        return failure(StatusCode::NOT_FOUND);
    };
    let Ok(metadata) = file.metadata() else {
        return failure(StatusCode::NOT_FOUND);
    };
    let size = metadata.len();
    if size == 0 {
        return failure(StatusCode::NOT_FOUND);
    }
    let range = request
        .headers()
        .get(header::RANGE)
        .and_then(|value| value.to_str().ok());
    let mut response = http::Response::builder()
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "null")
        .header(header::CACHE_CONTROL, "no-store");
    let (start, end) = if let Some(range) = range {
        let Some((first, last)) = range
            .strip_prefix("bytes=")
            .and_then(|range| range.split_once('-'))
        else {
            return failure(StatusCode::RANGE_NOT_SATISFIABLE);
        };
        let parsed = if first.is_empty() {
            last.parse::<u64>()
                .ok()
                .filter(|n| *n > 0)
                .map(|n| (size.saturating_sub(n), size - 1))
        } else {
            first.parse::<u64>().ok().and_then(|start| {
                let end = if last.is_empty() {
                    size - 1
                } else {
                    last.parse().ok()?
                };
                Some((start, end.min(size - 1)))
            })
        };
        let Some((start, end)) = parsed.filter(|(start, end)| start <= end) else {
            return failure(StatusCode::RANGE_NOT_SATISFIABLE);
        };
        let end = end.min(start.saturating_add(2 * 1024 * 1024 - 1));
        response = response
            .status(StatusCode::PARTIAL_CONTENT)
            .header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{size}"));
        (start, end)
    } else {
        if size > 16 * 1024 * 1024 {
            return failure(StatusCode::PAYLOAD_TOO_LARGE);
        }
        (0, size - 1)
    };
    let length = end - start + 1;
    let mut bytes = Vec::with_capacity(length as usize);
    if file.seek(SeekFrom::Start(start)).is_err()
        || file.take(length).read_to_end(&mut bytes).is_err()
        || bytes.len() != length as usize
    {
        return failure(StatusCode::NOT_FOUND);
    }
    response
        .header(header::CONTENT_LENGTH, length)
        .body(bytes)
        .unwrap()
}

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug)]
enum UserEvent {
    Eval(String),
    #[cfg(windows)]
    Tray(tray::Action),
    #[cfg(windows)]
    TrayStatus(tray::Status),
}

#[derive(Deserialize)]
struct IpcMessage {
    #[serde(default)]
    req_id: Option<u64>,
    action: String,
    #[serde(default)]
    payload: Value,
}

#[derive(Deserialize)]
struct WakeConfig {
    port: u16,
    token: String,
}

struct ServiceHandle {
    child: Mutex<Option<Child>>,
    paused: Mutex<bool>,
}

impl ServiceHandle {
    fn new() -> Self {
        Self {
            child: Mutex::new(None),
            paused: Mutex::new(false),
        }
    }

    fn is_running(&self, data_dir: &Path) -> bool {
        if *self.paused.lock().unwrap() {
            return false;
        }
        if let Ok(mut guard) = self.child.lock()
            && let Some(child) = guard.as_mut()
        {
            match child.try_wait() {
                Ok(None) => return true,
                _ => {
                    *guard = None;
                }
            }
        }
        daemon_reachable(data_dir)
    }

    fn start(&self, cli: &Path, data_dir: &Path) {
        *self.paused.lock().unwrap() = false;
        if self.is_running(data_dir) {
            return;
        }
        let mut cmd = hidden_command(cli);
        cmd.arg("serve")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        if let Ok(child) = cmd.spawn() {
            *self.child.lock().unwrap() = Some(child);
        }
    }

    fn pause(&self) {
        *self.paused.lock().unwrap() = true;
        if let Ok(mut guard) = self.child.lock()
            && let Some(mut child) = guard.take()
        {
            let _ = child.kill();
            let _ = child.wait();
        }
        #[cfg(windows)]
        {
            let mut killer = hidden_command(Path::new("taskkill"));
            let _ = killer
                .args(["/F", "/IM", "portal-drop.exe"])
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
        }
    }
}

fn hidden_command(program: &Path) -> Command {
    #[allow(unused_mut)]
    let mut cmd = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

fn cli_binary() -> PathBuf {
    if let Ok(explicit) = env::var("PORTAL_DROP_BINARY") {
        return PathBuf::from(explicit);
    }
    let name = if cfg!(windows) {
        "portal-drop.exe"
    } else {
        "portal-drop"
    };
    if let Ok(exe) = env::current_exe()
        && let Some(dir) = exe.parent()
    {
        let sibling = dir.join(name);
        if sibling.is_file() {
            return sibling;
        }
    }
    PathBuf::from(name)
}

fn data_dir() -> PathBuf {
    if let Ok(explicit) = env::var("PORTAL_DROP_DATA_DIR") {
        return PathBuf::from(explicit);
    }
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("portal-drop")
}

fn daemon_reachable(dir: &Path) -> bool {
    let Ok(raw) = fs::read_to_string(dir.join("wake.json")) else {
        return false;
    };
    let Ok(cfg) = serde_json::from_str::<WakeConfig>(&raw) else {
        return false;
    };
    TcpStream::connect_timeout(
        &([127, 0, 0, 1], cfg.port).into(),
        Duration::from_millis(200),
    )
    .is_ok()
}

fn run_cli(cli: &Path, args: &[String], stdin_data: Option<&str>) -> Result<String, String> {
    let mut cmd = hidden_command(cli);
    cmd.args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .stdin(if stdin_data.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        });
    let mut child = cmd.spawn().map_err(|e| e.to_string())?;
    if let Some(text) = stdin_data
        && let Some(mut input) = child.stdin.take()
    {
        let _ = input.write_all(text.as_bytes());
    }
    let output = child.wait_with_output().map_err(|e| e.to_string())?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    } else {
        let err = String::from_utf8_lossy(&output.stderr).trim().to_owned();
        Err(if err.is_empty() {
            format!("Command failed with status {}", output.status)
        } else {
            err
        })
    }
}

fn prefs_path(data_dir: &Path) -> PathBuf {
    data_dir.join("ui-prefs.json")
}

fn load_prefs(data_dir: &Path) -> Value {
    fs::read_to_string(prefs_path(data_dir))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .filter(|v| v.is_object())
        .unwrap_or_else(|| json!({ "theme": "light" }))
}

fn save_prefs(data_dir: &Path, prefs: &Value) -> Result<(), String> {
    let path = prefs_path(data_dir);
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let body = serde_json::to_string_pretty(prefs).map_err(|e| e.to_string())?;
    fs::write(&path, body).map_err(|e| e.to_string())
}

fn show_in_folder(path: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        hidden_command(Path::new("explorer"))
            .arg(format!("/select,{path}"))
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let p = Path::new(path);
        let dir = p.parent().unwrap_or(p);
        open_path(&dir.to_string_lossy())
    }
}

fn open_path(path: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        hidden_command(Path::new("cmd"))
            .args(["/C", "start", "", path])
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(not(windows))]
    {
        Command::new("xdg-open")
            .arg(path)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
}

fn handle_notification_cli() -> bool {
    let args: Vec<String> = env::args().collect();
    if args.len() < 3 {
        return false;
    }
    let (title, fallback_body) = match args[1].as_str() {
        "--notify-chat" => ("New message", "New message received"),
        "--notify-file" => ("File received", "File saved in Downloads\\Portal Drop"),
        "--notify-connected" => ("Device connected", "Paired device is now connected"),
        _ => return false,
    };
    let mut stdin_buf = String::new();
    let _ = std::io::stdin().read_to_string(&mut stdin_buf);
    let body = stdin_buf.trim();
    let body = if body.is_empty() { fallback_body } else { body };
    show_os_toast(title, body);
    true
}

#[cfg(windows)]
fn show_os_toast(title: &str, body: &str) {
    let esc = |s: &str| {
        s.replace('&', "&amp;")
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
        esc(body),
    );
    let _ = hidden_command(Path::new("powershell"))
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
        .status();
}

#[cfg(not(windows))]
fn show_os_toast(_title: &str, _body: &str) {}

fn js_call(proxy: &EventLoopProxy<UserEvent>, fn_name: &str, arg: &Value) {
    if let Ok(encoded) = serde_json::to_string(arg) {
        let script = format!("window.{fn_name} && window.{fn_name}({encoded});");
        let _ = proxy.send_event(UserEvent::Eval(script));
    }
}

fn spawn_wake_watcher(dir: PathBuf, proxy: EventLoopProxy<UserEvent>) {
    thread::spawn(move || {
        loop {
            let wake_path = dir.join("wake.json");
            let connected = (|| -> std::io::Result<()> {
                let raw = fs::read_to_string(&wake_path)?;
                let cfg: WakeConfig = serde_json::from_str(&raw).map_err(std::io::Error::other)?;
                let mut stream = TcpStream::connect_timeout(
                    &([127, 0, 0, 1], cfg.port).into(),
                    Duration::from_secs(2),
                )?;
                writeln!(stream, "watch {}", cfg.token)?;
                let reader = BufReader::new(stream);
                for line in reader.lines() {
                    let event = line?;
                    let trimmed = event.trim();
                    if !trimmed.is_empty() && trimmed != "ping" {
                        js_call(&proxy, "__portalWake", &json!({ "reason": trimmed }));
                    }
                }
                Ok(())
            })();
            if connected.is_err() {
                thread::sleep(Duration::from_millis(1500));
            } else {
                thread::sleep(Duration::from_millis(400));
            }
        }
    });
}

fn dispatch_ipc(
    msg: IpcMessage,
    cli: PathBuf,
    dir: PathBuf,
    svc: Arc<ServiceHandle>,
    proxy: EventLoopProxy<UserEvent>,
    tray_available: bool,
) {
    thread::spawn(move || {
        let result: Result<Value, String> = match msg.action.as_str() {
            "snapshot" => {
                let chat_id = msg.payload["chatId"]
                    .as_str()
                    .unwrap_or("00000000-0000-0000-0000-000000000001")
                    .to_owned();
                let active = svc.is_running(&dir);
                run_cli(
                    &cli,
                    &[
                        "snapshot".into(),
                        "--chat".into(),
                        chat_id,
                        "--limit".into(),
                        "50".into(),
                    ],
                    None,
                )
                .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
                .map(|snap| {
                    json!({
                        "serviceActive": active,
                        "trayAvailable": tray_available,
                        "status": snap["status"],
                        "chats": snap["chats"],
                        "chatId": snap["chat_id"],
                        "history": snap["history"],
                        "progress": snap["progress"],
                        "previews": snap.get("previews").cloned().unwrap_or_else(|| json!([])),
                    })
                })
            }
            "load_older" => {
                let chat_id = msg.payload["chatId"]
                    .as_str()
                    .unwrap_or_default()
                    .to_owned();
                let before = msg.payload["before"].as_i64().unwrap_or(0).to_string();
                run_cli(
                    &cli,
                    &[
                        "history".into(),
                        "--json".into(),
                        "--chat".into(),
                        chat_id,
                        "--before".into(),
                        before,
                        "--limit".into(),
                        "50".into(),
                    ],
                    None,
                )
                .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "search_messages" => {
                let query = msg.payload["query"].as_str().unwrap_or_default().to_owned();
                run_cli(&cli, &["search".into(), "--".into(), query], None)
                    .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "send_text" | "send_image" => {
                let chat_id = msg.payload["chatId"]
                    .as_str()
                    .unwrap_or_default()
                    .to_owned();
                let is_text = msg.action == "send_text";
                let data = msg.payload[if is_text { "text" } else { "base64" }]
                    .as_str()
                    .unwrap_or_default();
                let command = if is_text { "send-text" } else { "send-image" };
                let mut args = vec![command.into(), "--chat".into(), chat_id];
                if let Some(reply) = msg.payload["replyTo"].as_str() {
                    args.push("--reply-to".into());
                    args.push(reply.to_owned());
                }
                if let Some(ttl) = msg.payload["ttl"].as_u64() {
                    args.push("--ttl".into());
                    args.push(ttl.to_string());
                }
                if is_text {
                    args.push("-".into());
                }
                run_cli(&cli, &args, Some(data)).map(|_| json!({ "ok": true }))
            }
            "pick_files" => {
                let chosen = rfd::FileDialog::new()
                    .set_title("Choose files to send")
                    .pick_files();
                if let Some(paths) = chosen {
                    queue_files(&cli, &msg.payload, paths)
                } else {
                    Ok(json!({ "cancelled": true }))
                }
            }
            "pick_folder" => {
                let mut dialog = rfd::FileDialog::new().set_title("Choose save folder");
                if let Some(cur) = msg.payload["current"].as_str() {
                    let p = Path::new(cur);
                    if p.is_dir() {
                        dialog = dialog.set_directory(p);
                    }
                }
                if let Some(path) = dialog.pick_folder() {
                    Ok(json!({ "path": path.to_string_lossy() }))
                } else {
                    Ok(json!({ "cancelled": true }))
                }
            }
            "send_files" => {
                let paths: Vec<PathBuf> = msg.payload["paths"]
                    .as_array()
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|v| v.as_str().map(PathBuf::from))
                            .collect()
                    })
                    .unwrap_or_default();
                queue_files(&cli, &msg.payload, paths)
            }
            "new_chat" => run_cli(&cli, &["new-chat".into()], None)
                .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string())),
            "rename_chat" => {
                let id = msg.payload["id"].as_str().unwrap_or_default().to_owned();
                let title = msg.payload["title"].as_str().unwrap_or_default().to_owned();
                run_cli(&cli, &["rename-chat".into(), id, title], None)
                    .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "delete_chat" => {
                let id = msg.payload["id"].as_str().unwrap_or_default().to_owned();
                run_cli(&cli, &["delete-chat".into(), id], None)
                    .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "message_text" => {
                let id = msg.payload["id"].as_str().unwrap_or_default().to_owned();
                run_cli(&cli, &["message-text".into(), id], None)
                    .map(|text| json!({ "text": text }))
            }
            "delete_message" => {
                let id = msg.payload["id"].as_str().unwrap_or_default().to_owned();
                let mut args = vec!["delete-message".into()];
                if msg.payload["deleteFiles"].as_bool() == Some(true) {
                    args.push("--delete-files".into());
                }
                args.push(id);
                run_cli(&cli, &args, None).map(|_| json!({ "ok": true }))
            }
            "delete_messages" => {
                let ids: Vec<String> = msg.payload["ids"]
                    .as_array()
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|v| v.as_str().map(ToOwned::to_owned))
                            .collect()
                    })
                    .unwrap_or_default();
                if ids.is_empty() {
                    Ok(json!({ "ok": true }))
                } else {
                    let mut args = vec!["delete-message".into()];
                    if msg.payload["deleteFiles"].as_bool() == Some(true) {
                        args.push("--delete-files".into());
                    }
                    args.extend(ids);
                    run_cli(&cli, &args, None).map(|_| json!({ "ok": true }))
                }
            }
            "shared_media" => {
                let mut args = vec![
                    "shared-media".into(),
                    msg.payload["tab"].as_str().unwrap_or("files").into(),
                ];
                if let Some(chat) = msg.payload["chatId"].as_str() {
                    args.extend(["--chat".into(), chat.into()]);
                }
                if let Some(before) = msg.payload["before"].as_i64() {
                    args.extend(["--before".into(), before.to_string()]);
                }
                run_cli(&cli, &args, None)
                    .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "transfer_control" => {
                let args = [
                    "transfer-control".into(),
                    msg.payload["direction"].as_str().unwrap_or_default().into(),
                    msg.payload["id"].as_str().unwrap_or_default().into(),
                    msg.payload["action"].as_str().unwrap_or_default().into(),
                ];
                run_cli(&cli, &args, None)
                    .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "received_files" => {
                let mut args = vec![
                    "history".into(),
                    "--json".into(),
                    "--files".into(),
                    "--limit".into(),
                    "50".into(),
                ];
                if let Some(before) = msg.payload["before"].as_i64() {
                    args.push("--before".into());
                    args.push(before.to_string());
                }
                run_cli(&cli, &args, None)
                    .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "open_file" => {
                let path = msg.payload["path"].as_str().unwrap_or_default();
                open_path(path).map(|()| json!({ "ok": true }))
            }
            "show_in_folder" => {
                let path = msg.payload["path"].as_str().unwrap_or_default();
                show_in_folder(path).map(|()| json!({ "ok": true }))
            }
            "open_url" => {
                let url = msg.payload["url"].as_str().unwrap_or_default();
                if url.starts_with("http://") || url.starts_with("https://") {
                    open_path(url).map(|()| json!({ "ok": true }))
                } else {
                    Err("Unsupported URL scheme".into())
                }
            }
            "open_receive_folder" => run_cli(&cli, &["receive-folder".into()], None)
                .and_then(|path| open_path(path.trim()))
                .map(|()| json!({ "ok": true })),
            "set_theme" => Ok(json!({ "ok": true })),
            "set_prefs" => {
                let prefs = msg
                    .payload
                    .get("prefs")
                    .cloned()
                    .unwrap_or_else(|| json!({}));
                save_prefs(&dir, &prefs).map(|()| json!({ "ok": true }))
            }
            "get_settings" => run_cli(&cli, &["settings".into()], None)
                .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string())),
            "set_settings" => {
                let settings = msg
                    .payload
                    .get("settings")
                    .cloned()
                    .unwrap_or_else(|| json!({}));
                serde_json::to_string(&settings)
                    .map_err(|e| e.to_string())
                    .and_then(|body| {
                        run_cli(
                            &cli,
                            &["settings".into(), "--stdin-json".into()],
                            Some(&body),
                        )
                        .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
                    })
            }
            "test_sound" => {
                let kind = msg.payload["kind"].as_str().unwrap_or("message").to_owned();
                run_cli(&cli, &["test-sound".into(), kind], None).map(|_| json!({ "ok": true }))
            }
            "clear_history" => {
                run_cli(&cli, &["clear-history".into()], None).map(|_| json!({ "ok": true }))
            }
            "lan_addresses" => run_cli(&cli, &["lan-addresses".into()], None)
                .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string())),
            "wifi_picker" => Ok(json!({ "available": cfg!(windows) })),
            #[cfg(windows)]
            "wifi_networks" => wifi::networks(msg.payload["scan"].as_bool().unwrap_or(false)),
            #[cfg(windows)]
            "wifi_join" => wifi::join(
                msg.payload["id"].as_str().unwrap_or_default(),
                msg.payload["password"].as_str(),
            )
            .inspect(|_| svc.start(&cli, &dir)),
            "announce_hotspot" => {
                let address = msg.payload["address"].as_str().unwrap_or_default();
                run_cli(
                    &cli,
                    &[
                        "announce-hotspot".into(),
                        "--address".into(),
                        address.into(),
                    ],
                    None,
                )
                .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|e| e.to_string()))
            }
            "wifi_settings" => hidden_command(Path::new("explorer.exe"))
                .arg("ms-settings:network-wifi")
                .spawn()
                .map(|_| json!({ "ok": true }))
                .map_err(|e| e.to_string()),
            "pair" => {
                svc.start(&cli, &dir);
                let mut args = vec!["pair".into()];
                if let Some(address) = msg.payload["address"].as_str().filter(|s| !s.is_empty()) {
                    args.extend(["--address".into(), address.into()]);
                }
                run_cli(&cli, &args, None).map(|output| {
                    let lines: Vec<&str> = output.trim_end_matches('\n').lines().collect();
                    let link = lines.last().copied().unwrap_or_default();
                    let qr = if lines.len() >= 2 {
                        lines[..lines.len() - 2].join("\n")
                    } else {
                        String::new()
                    };
                    json!({ "qr": qr, "link": link })
                })
            }
            "unpair" => run_cli(&cli, &["unpair".into()], None).map(|_| json!({ "ok": true })),
            "pause_service" => {
                svc.pause();
                Ok(json!({ "serviceActive": false }))
            }
            "resume_service" => {
                svc.start(&cli, &dir);
                Ok(json!({ "serviceActive": true }))
            }
            other => Err(format!("Unknown action: {other}")),
        };

        if let Some(req_id) = msg.req_id {
            let reply = match result {
                Ok(data) => json!({ "req_id": req_id, "ok": true, "data": data }),
                Err(error) => json!({ "req_id": req_id, "ok": false, "error": error }),
            };
            js_call(&proxy, "__portalReply", &reply);
        }
    });
}

fn queue_files(cli: &Path, payload: &Value, paths: Vec<PathBuf>) -> Result<Value, String> {
    let chat_id = payload["chatId"].as_str().unwrap_or_default().to_owned();
    let reply = payload["replyTo"].as_str().map(ToOwned::to_owned);
    let ttl = payload["ttl"].as_u64();
    let mut base_args = vec!["--chat".into(), chat_id];
    if let Some(r) = reply {
        base_args.push("--reply-to".into());
        base_args.push(r);
    }
    if let Some(t) = ttl {
        base_args.push("--ttl".into());
        base_args.push(t.to_string());
    }
    let mut sent = 0usize;
    let mut skipped = 0usize;
    let mut errors = Vec::new();
    for path in paths {
        if !path.is_file() && !path.is_dir() {
            skipped += 1;
            continue;
        }
        let folder = path.is_dir();
        let mut args = vec![if folder { "send-folder" } else { "send-file" }.into()];
        args.extend(base_args.iter().cloned());
        args.extend(["--".into(), path.to_string_lossy().into_owned()]);
        let queued = run_cli(cli, &args, None).and_then(|output| {
            if !folder {
                return Ok(1);
            }
            let result: Value = serde_json::from_str(&output).map_err(|error| error.to_string())?;
            result["sent"]
                .as_u64()
                .map(|count| count as usize)
                .ok_or_else(|| "Folder count unavailable".into())
        });
        match queued {
            Ok(count) => sent += count,
            Err(e) => errors.push(format!(
                "{}: {e}",
                path.file_name().unwrap_or_default().to_string_lossy()
            )),
        }
    }
    if !errors.is_empty() && sent == 0 {
        return Err(errors.join("\n"));
    }
    Ok(json!({ "sent": sent, "skipped": skipped, "errors": errors }))
}

fn main() -> wry::Result<()> {
    if handle_notification_cli() {
        return Ok(());
    }

    let cli = cli_binary();
    let dir = data_dir();
    let service = Arc::new(ServiceHandle::new());
    service.start(&cli, &dir);

    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    #[cfg(windows)]
    let tray = tray::Tray::new(&proxy).ok();
    #[cfg(windows)]
    let tray_available = tray.is_some();
    #[cfg(not(windows))]
    let tray_available = false;

    let window_icon = Icon::from_rgba(ICON_RGBA.to_vec(), 64, 64).ok();
    let window = WindowBuilder::new()
        .with_title("Portal Drop")
        .with_window_icon(window_icon)
        .with_inner_size(LogicalSize::new(1100.0, 740.0))
        .with_min_inner_size(LogicalSize::new(680.0, 480.0))
        .build(&event_loop)
        .expect("create Portal Drop window");

    let ipc_cli = cli.clone();
    let ipc_dir = dir.clone();
    let ipc_svc = service.clone();
    let ipc_proxy = proxy.clone();
    let dnd_proxy = proxy.clone();

    let initial_prefs = load_prefs(&dir);
    let media_cli = cli.clone();
    let media_requests = Arc::new(AtomicUsize::new(0));
    let init_script = format!(
        "window.__PORTAL_HOST = \"windows\";\nwindow.__PORTAL_FILE_PROTO = \"http://portal-media.localhost/\";\nwindow.__PORTAL_PREFS = {};\n",
        serde_json::to_string(&initial_prefs).unwrap_or_else(|_| "{\"theme\":\"light\"}".into())
    );

    let builder = WebViewBuilder::new()
        .with_initialization_script(&init_script)
        .with_html(UI_HTML)
        .with_asynchronous_custom_protocol("portal-media".into(), move |_, request, responder| {
            if media_requests.fetch_add(1, Ordering::Relaxed) >= 3 {
                media_requests.fetch_sub(1, Ordering::Relaxed);
                responder.respond(
                    http::Response::builder()
                        .status(http::StatusCode::SERVICE_UNAVAILABLE)
                        .body(Vec::new())
                        .unwrap(),
                );
                return;
            }
            let cli = media_cli.clone();
            let counter = media_requests.clone();
            thread::spawn(move || {
                responder.respond(serve_media(&cli, request));
                counter.fetch_sub(1, Ordering::Relaxed);
            });
        })
        .with_ipc_handler(move |request| {
            if let Ok(msg) = serde_json::from_str::<IpcMessage>(request.body()) {
                dispatch_ipc(
                    msg,
                    ipc_cli.clone(),
                    ipc_dir.clone(),
                    ipc_svc.clone(),
                    ipc_proxy.clone(),
                    tray_available,
                );
            }
        })
        .with_drag_drop_handler(move |event| {
            match event {
                DragDropEvent::Enter { paths, .. } => {
                    let names: Vec<String> = paths
                        .iter()
                        .filter_map(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()))
                        .collect();
                    js_call(&dnd_proxy, "__portalDragEnter", &json!({ "names": names }));
                }
                DragDropEvent::Leave => {
                    js_call(&dnd_proxy, "__portalDragLeave", &json!({}));
                }
                DragDropEvent::Drop { paths, .. } => {
                    let list: Vec<String> = paths
                        .into_iter()
                        .map(|p| p.to_string_lossy().into_owned())
                        .collect();
                    js_call(&dnd_proxy, "__portalDropFiles", &json!({ "paths": list }));
                }
                _ => {}
            }
            true
        });

    #[cfg(not(target_os = "linux"))]
    let webview = builder.build(&window)?;
    #[cfg(target_os = "linux")]
    let webview = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.gtk_window())?
    };

    #[cfg(windows)]
    let tray_focused = tray
        .as_ref()
        .map(|_| tray::watch(cli.clone(), dir.clone(), service.clone(), proxy.clone()));
    spawn_wake_watcher(dir.clone(), proxy.clone());

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(UserEvent::Eval(script)) => {
                let _ = webview.evaluate_script(&script);
            }
            #[cfg(windows)]
            Event::UserEvent(UserEvent::Tray(action)) => match action {
                tray::Action::Open => {
                    window.set_visible(true);
                    window.set_minimized(false);
                    window.set_focus();
                }
                tray::Action::Clipboard => tray::send_clipboard(cli.clone()),
                tray::Action::ToggleService => {
                    let (cli, dir, service, proxy) =
                        (cli.clone(), dir.clone(), service.clone(), proxy.clone());
                    thread::spawn(move || {
                        if service.is_running(&dir) {
                            service.pause();
                        } else {
                            service.start(&cli, &dir);
                        }
                        js_call(&proxy, "__portalWake", &json!({ "reason": "tray" }));
                    });
                }
                tray::Action::Quit => *control_flow = ControlFlow::Exit,
            },
            #[cfg(windows)]
            Event::UserEvent(UserEvent::TrayStatus(status)) => {
                if let Some(tray) = &tray {
                    tray.render(status);
                }
            }
            #[cfg(windows)]
            Event::WindowEvent {
                event: WindowEvent::Focused(focused),
                ..
            } => {
                if let Some(flag) = &tray_focused {
                    flag.store(focused, std::sync::atomic::Ordering::Relaxed);
                }
            }
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => {
                if tray_available && load_prefs(&dir)["minimizeToTray"] == true {
                    window.set_visible(false);
                    #[cfg(windows)]
                    if let Some(flag) = &tray_focused {
                        flag.store(false, std::sync::atomic::Ordering::Relaxed);
                    }
                } else {
                    *control_flow = ControlFlow::Exit;
                }
            }
            _ => {}
        }
    });
}
