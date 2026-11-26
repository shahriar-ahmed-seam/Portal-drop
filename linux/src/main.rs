mod files;
mod folders;
mod model;
mod network;
mod server;
mod sound;
mod store;
#[cfg(target_os = "linux")]
mod watchdog;

use anyhow::{Context, Result, bail, ensure};
use clap::{Parser, Subcommand, ValueEnum};
use files::{Files, preview_file, preview_text};
use fs2::FileExt;
use model::{
    Chat, Item, Kind, MAX_TTL_SEC, MIN_TTL_SEC, TEMP_TTL_SEC, TransferAction, TransferDirection,
    TransferResponse, TransferState, valid_id,
};
use qrcode::{QrCode, render::unicode};
use serde::Serialize;
use std::{
    fs::{File, OpenOptions},
    io::{self, IsTerminal, Read, Write},
    net::{Ipv4Addr, SocketAddr},
    path::PathBuf,
};
use store::{HistoryEntry, HistoryQuery, Store, private_directory};

#[derive(Parser)]
#[command(
    version,
    about = "Pair with Android and send files and messages over your LAN"
)]
struct Args {
    /// Directory for the identity, chat database, and outgoing files
    #[arg(long, env = "PORTAL_DROP_DATA_DIR", global = true)]
    data_dir: Option<PathBuf>,
    /// Where completed incoming files are saved
    #[arg(long, env = "PORTAL_DROP_RECEIVE_DIR", global = true)]
    receive_dir: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}

#[derive(Clone, Copy, ValueEnum)]
enum MediaTab {
    PhotosVideos,
    Files,
    Links,
}

#[derive(Subcommand)]
enum Command {
    /// Run the background service
    Serve {
        #[arg(long, env = "PORTAL_DROP_LISTEN", default_value = "0.0.0.0:53621")]
        listen: SocketAddr,
        /// Disable LAN service discovery
        #[arg(long)]
        no_discovery: bool,
    },
    /// Display a single-use pairing QR code and link
    Pair {
        /// Desktop address as seen by Android, with an optional port
        #[arg(long)]
        address: Option<String>,
        /// Print the pairing link without rendering the QR code
        #[arg(long)]
        print_only: bool,
    },
    /// List the desktop's reachable local address candidates as JSON
    LanAddresses,
    /// Send a discovery hint on the selected Wi-Fi adapter
    AnnounceHotspot {
        /// Desktop Wi-Fi IPv4 address, optionally with its service port
        #[arg(long)]
        address: String,
        /// The phone's on-link IPv4 gateway from the selected Wi-Fi connection
        #[arg(long)]
        gateway: Option<Ipv4Addr>,
    },
    /// Render a Wi-Fi QR code from a WIFI: payload provided on standard input
    WifiQr,
    /// Revoke the current Android device and cancel unused invitations
    Unpair,
    /// Show whether an Android device is paired and connected
    Status {
        #[arg(long)]
        json: bool,
    },
    /// Send a text message, or pass "-" to read from standard input
    SendText {
        #[arg(long)]
        chat: Option<String>,
        #[arg(long)]
        reply_to: Option<String>,
        /// Auto-delete from both devices after 10 minutes
        #[arg(long)]
        temp: bool,
        /// Auto-delete from both devices after this many seconds
        #[arg(long, value_parser = clap::value_parser!(u64).range(MIN_TTL_SEC..=MAX_TTL_SEC))]
        ttl: Option<u64>,
        #[arg(allow_hyphen_values = true)]
        text: String,
    },
    /// Queue a copy of a file from disk
    SendFile {
        #[arg(long)]
        chat: Option<String>,
        #[arg(long)]
        reply_to: Option<String>,
        /// Auto-delete from both devices after 10 minutes
        #[arg(long)]
        temp: bool,
        /// Auto-delete from both devices after this many seconds
        #[arg(long, value_parser = clap::value_parser!(u64).range(MIN_TTL_SEC..=MAX_TTL_SEC))]
        ttl: Option<u64>,
        path: PathBuf,
    },
    /// Queue all ordinary files in a folder with their relative paths
    SendFolder {
        #[arg(long)]
        chat: Option<String>,
        #[arg(long)]
        reply_to: Option<String>,
        #[arg(long, value_parser = clap::value_parser!(u64).range(MIN_TTL_SEC..=MAX_TTL_SEC))]
        ttl: Option<u64>,
        path: PathBuf,
    },
    /// Queue a base64-encoded clipboard image supplied on standard input
    SendImage {
        #[arg(long)]
        chat: Option<String>,
        #[arg(long)]
        reply_to: Option<String>,
        #[arg(long, value_parser = clap::value_parser!(u64).range(MIN_TTL_SEC..=MAX_TTL_SEC))]
        ttl: Option<u64>,
    },
    /// List saved chats
    Chats {
        #[arg(long)]
        json: bool,
    },
    /// Create a chat with an optional title
    NewChat { title: Option<String> },
    /// Change a chat's title
    RenameChat { id: String, title: String },
    /// Delete a chat and its messages
    DeleteChat { id: String },
    /// Show local chat and transfer history
    History {
        #[arg(long)]
        json: bool,
        #[arg(long)]
        chat: Option<String>,
        #[arg(long)]
        before: Option<i64>,
        #[arg(long, default_value_t = 50, value_parser = clap::value_parser!(u32).range(1..=200))]
        limit: u32,
        /// Show received files, respecting cleared transfer history
        #[arg(long)]
        files: bool,
    },
    /// Search message bodies and filenames across chats, returning JSON
    Search {
        #[arg(long, default_value_t = 50, value_parser = clap::value_parser!(u32).range(1..=100))]
        limit: u32,
        #[arg(allow_hyphen_values = true)]
        query: String,
    },
    /// List shared media, files, or linked messages as a page of JSON
    SharedMedia {
        tab: MediaTab,
        #[arg(long)]
        chat: Option<String>,
        #[arg(long)]
        before: Option<i64>,
        #[arg(long, default_value_t = 30, value_parser = clap::value_parser!(u32).range(1..=100))]
        limit: u32,
    },
    /// Return the local path for an available photo or video
    MediaPath { id: String },
    /// Show new local timeline entries after the given sequence
    Events {
        #[arg(long)]
        json: bool,
        #[arg(long, default_value_t = 0)]
        after: i64,
        #[arg(long, default_value_t = 50, value_parser = clap::value_parser!(u32).range(1..=200))]
        limit: u32,
    },
    /// Return status, chats, active chat history, and transfer progress in one fast JSON call
    Snapshot {
        #[arg(long)]
        chat: Option<String>,
        #[arg(long, default_value_t = 40, value_parser = clap::value_parser!(u32).range(1..=200))]
        limit: u32,
    },
    /// Show current transfer offsets
    Progress {
        #[arg(long)]
        json: bool,
    },
    /// Change a pending file or long-message transfer
    TransferControl {
        direction: TransferDirection,
        id: String,
        action: TransferAction,
    },
    /// Print the exact UTF-8 message body without an added newline
    MessageText { id: String },
    /// Remove one or more messages/files from the local timeline
    DeleteMessage {
        /// Also delete already-saved received files from the receive folder
        #[arg(long)]
        delete_files: bool,
        #[arg(required = true)]
        ids: Vec<String>,
    },
    /// Print the folder where incoming files are saved
    ReceiveFolder,
    /// Clear records of received files from the history list
    ClearHistory,
    /// Get or update notification, sound, and save-folder preferences (JSON)
    Settings {
        /// Read full settings JSON from stdin
        #[arg(long)]
        stdin_json: bool,
        #[arg(long)]
        message_notify: Option<bool>,
        #[arg(long)]
        message_sound: Option<bool>,
        #[arg(long)]
        file_notify: Option<bool>,
        #[arg(long)]
        file_sound: Option<bool>,
        #[arg(long)]
        connected_notify: Option<bool>,
        #[arg(long)]
        connected_sound: Option<bool>,
        /// Custom folder where received files are saved
        #[arg(long = "save-dir")]
        save_dir: Option<String>,
        /// Reset save folder back to the default Downloads/Portal Drop location
        #[arg(long)]
        reset_save_dir: bool,
    },
    /// Preview one of the notification sounds (message, file, connected)
    TestSound { kind: String },
}

fn main() -> Result<()> {
    let args = Args::parse();
    let data = match args.data_dir {
        Some(path) => std::path::absolute(path)?,
        None => dirs::data_dir()
            .context("set --data-dir for this desktop")?
            .join("portal-drop"),
    };
    let receive_override = args.receive_dir.is_some();
    let receive = match args.receive_dir {
        Some(path) => std::path::absolute(path)?,
        None => dirs::download_dir()
            .or_else(|| dirs::home_dir().map(|home| home.join("Downloads")))
            .context("set --receive-dir for this desktop")?
            .join("Portal Drop"),
    };
    let store = Store::initialize(&data)?;
    let files = Files::new(&data, receive, receive_override);
    match args.command {
        Command::Serve {
            listen,
            no_discovery,
        } => {
            private_directory(&data)?;
            let lock = open_lock_file(&data.join("daemon.lock"))?;
            FileExt::try_lock_exclusive(&lock)
                .context("Portal Drop is already using this data directory")?;
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()?;
            runtime.block_on(server::serve(store, files, listen, !no_discovery))?;
        }
        Command::Pair {
            address,
            print_only,
        } => {
            let address = network::pairing_address(&data, address.as_deref())?;
            let identity = store.identity()?;
            let code = store.issue_invite()?;
            let invite = format!(
                "portaldrop://{address}/pair?v=1&fp={}&code={code}",
                identity.fingerprint,
            );
            if !print_only {
                let qr = QrCode::new(invite.as_bytes())?
                    .render::<unicode::Dense1x2>()
                    .build();
                if io::stdout().is_terminal() && std::env::var_os("NO_COLOR").is_none() {
                    println!("\x1b[30;107m{qr}\x1b[0m");
                } else {
                    println!("{qr}");
                }
                println!("Scan or paste this single-use link. It expires in 10 minutes.");
            }
            println!("{invite}");
        }
        Command::LanAddresses => print_json(&network::lan_addresses(&data)?)?,
        Command::AnnounceHotspot { address, gateway } => {
            let peer = store
                .active_peer()?
                .context("pair your phone before announcing this computer")?;
            network::announce_hotspot(
                &data,
                &address,
                gateway,
                &peer,
                &store.identity()?.fingerprint,
            )?;
            print_json(&serde_json::json!({ "sent": true }))?;
        }
        Command::WifiQr => {
            let mut data = String::new();
            io::stdin().lock().take(513).read_to_string(&mut data)?;
            let payload = data.trim_end_matches(['\r', '\n']);
            ensure!(
                data.len() <= 512 && payload.starts_with("WIFI:") && payload.ends_with(";;"),
                "invalid Wi-Fi QR payload"
            );
            let qr = QrCode::new(payload.as_bytes())?
                .render::<unicode::Dense1x2>()
                .build();
            print_json(&serde_json::json!({ "qr": qr }))?;
        }
        Command::Unpair => {
            store.unpair()?;
            server::wake_daemon(&data);
            println!("Android device unpaired.");
        }
        Command::Status { json } => {
            let mut status = store.status_info()?;
            server::add_local_route_status(&data, &mut status);
            if json {
                print_json(&status)?;
            } else {
                println!("{}", if status.paired { "Paired" } else { "Unpaired" });
            }
        }
        Command::SendText {
            chat,
            reply_to,
            temp,
            ttl,
            text,
        } => {
            let peer = store
                .active_peer()?
                .context("pair with Android before sending")?;
            let item = if text == "-" {
                files.stage_text(io::stdin().lock())?
            } else {
                files.stage_text(text.as_bytes())?
            };
            let effective_ttl = ttl.or_else(|| temp.then_some(TEMP_TTL_SEC));
            queue(&store, &files, &peer, item, chat, reply_to, effective_ttl)?;
            server::wake_daemon(&data);
        }
        Command::SendFile {
            chat,
            reply_to,
            temp,
            ttl,
            path,
        } => {
            let peer = store
                .active_peer()?
                .context("pair with Android before sending")?;
            let item = files.stage_outgoing(&path)?;
            let effective_ttl = ttl.or_else(|| temp.then_some(TEMP_TTL_SEC));
            queue(&store, &files, &peer, item, chat, reply_to, effective_ttl)?;
            server::wake_daemon(&data);
        }
        Command::SendFolder {
            chat,
            reply_to,
            ttl,
            path,
        } => {
            let peer = store
                .active_peer()?
                .context("pair with Android before sending")?;
            let children = folders::children(&path)?;
            let mut staged = Vec::with_capacity(children.len());
            let result = (|| {
                for (source, relative_path) in children {
                    let mut item = files.stage_outgoing(&source)?;
                    item.name = relative_path
                        .rsplit_once('/')
                        .map(|(_, name)| name.to_owned());
                    item.relative_path = Some(relative_path);
                    item.chat_id = chat.clone();
                    item.reply_to = reply_to.clone();
                    item.ttl_sec = ttl;
                    staged.push(item);
                }
                store.queue_batch(&peer, &staged)
            })();
            if let Err(error) = result {
                for item in &staged {
                    files.remove_outgoing(item);
                }
                return Err(error);
            }
            server::wake_daemon(&data);
            print_json(&serde_json::json!({ "sent": staged.len() }))?;
        }
        Command::SendImage {
            chat,
            reply_to,
            ttl,
        } => {
            let peer = store
                .active_peer()?
                .context("pair with Android before sending")?;
            let item = files.stage_clipboard_image(io::stdin().lock())?;
            queue(&store, &files, &peer, item, chat, reply_to, ttl)?;
            server::wake_daemon(&data);
        }
        Command::Chats { json } => {
            let chats = store.chats(false)?;
            if json {
                print_json(&chats)?;
            } else {
                for chat in chats {
                    println!("{}  {}", chat.id, chat.title);
                }
            }
        }
        Command::NewChat { title } => {
            let chat = Chat::new(title)?;
            store.write_chat(None, &chat, None)?;
            server::wake_daemon(&data);
            print_json(&chat)?;
        }
        Command::RenameChat { id, title } => {
            let chat = store.rename_chat(&id, title)?;
            server::wake_daemon(&data);
            print_json(&chat)?;
        }
        Command::DeleteChat { id } => {
            let chat = store.delete_chat(&id, &files)?;
            server::wake_daemon(&data);
            print_json(&chat)?;
        }
        Command::History {
            json,
            chat,
            before,
            limit,
            files: received_files,
        } => {
            let _ = store.purge_expired(&files);
            let mut rows = store.history(HistoryQuery {
                chat: chat.as_deref(),
                cursor: before.unwrap_or(i64::MAX),
                limit: limit + 1,
                after: false,
                files: received_files,
                include_hidden: json && !received_files,
            })?;
            let has_more = rows.len() > limit as usize;
            rows.truncate(limit as usize);
            if json {
                let next_before = has_more.then(|| rows.last().expect("nonempty page").seq);
                let items = rows
                    .into_iter()
                    .map(|row| message_view(&files, row))
                    .collect();
                print_json(&HistoryPage { items, next_before })?;
            } else {
                for row in rows.into_iter().rev() {
                    print_message(&message_view(&files, row));
                }
            }
        }
        Command::Search { limit, query } => {
            let query = query.trim().to_ascii_lowercase();
            ensure!(
                !query.is_empty() && query.len() <= 1024,
                "enter a search of up to 1,024 bytes"
            );
            let mut before = i64::MAX;
            let mut found = Vec::new();
            while found.len() < limit as usize {
                let candidates = store.search_candidates(&query, before)?;
                if candidates.is_empty() {
                    break;
                }
                for entry in candidates {
                    before = entry.seq;
                    if entry.item.text_size.is_some()
                        && !text_file_contains(&text_path(&files, &entry), query.as_bytes())
                    {
                        continue;
                    }
                    found.push(message_view(&files, entry));
                    if found.len() == limit as usize {
                        break;
                    }
                }
            }
            print_json(&found)?;
        }
        Command::SharedMedia {
            tab,
            chat,
            before,
            limit,
        } => {
            let mut cursor = before.unwrap_or(i64::MAX);
            let mut items = Vec::new();
            // Bound disk scans for messages stored separately from the database.
            let mut next_before = None;
            for _ in 0..8 {
                let candidates = store.media_candidates(
                    chat.as_deref(),
                    cursor,
                    matches!(tab, MediaTab::Links),
                )?;
                if candidates.is_empty() {
                    next_before = None;
                    break;
                }
                for entry in candidates {
                    cursor = entry.seq;
                    let media = entry.item.name.as_deref().and_then(media_type).is_some();
                    let include = match tab {
                        MediaTab::PhotosVideos => media,
                        MediaTab::Files => !media,
                        MediaTab::Links => {
                            entry.item.text_size.is_none() || {
                                let path = text_path(&files, &entry);
                                text_file_contains(&path, b"https://")
                                    || text_file_contains(&path, b"http://")
                            }
                        }
                    };
                    if include {
                        items.push(message_view(&files, entry));
                    }
                    if items.len() == limit as usize {
                        break;
                    }
                }
                next_before = Some(cursor);
                if items.len() == limit as usize {
                    break;
                }
            }
            print_json(&HistoryPage { items, next_before })?;
        }
        Command::MediaPath { id } => {
            ensure!(valid_id(&id), "invalid message ID");
            let entry = store
                .message(&id)?
                .context("message is no longer available")?;
            let mime = media_type(entry.item.name.as_deref().unwrap_or_default())
                .context("unsupported preview format")?;
            let path = message_view(&files, entry)
                .path
                .context("media is unavailable")?;
            print_json(&serde_json::json!({ "path": path, "mime": mime }))?;
        }
        Command::Events { json, after, limit } => {
            let _ = store.purge_expired(&files);
            let rows = store.history(HistoryQuery {
                chat: None,
                cursor: after,
                limit,
                after: true,
                files: false,
                include_hidden: true,
            })?;
            let items: Vec<_> = rows
                .into_iter()
                .map(|row| message_view(&files, row))
                .collect();
            if json {
                print_json(&items)?;
            } else {
                for item in items {
                    print_message(&item);
                }
            }
        }
        Command::Snapshot { chat, limit } => {
            let _ = store.purge_expired(&files);
            let mut status = store.status_info()?;
            server::add_local_route_status(&data, &mut status);
            let chats = store.chats(false)?;
            let target_chat = chat
                .as_deref()
                .filter(|id| chats.iter().any(|c| c.id == *id))
                .or_else(|| chats.first().map(|c| c.id.as_str()))
                .unwrap_or(model::DEFAULT_CHAT)
                .to_owned();
            let mut rows = store.history(HistoryQuery {
                chat: Some(&target_chat),
                cursor: i64::MAX,
                limit: limit + 1,
                after: false,
                files: false,
                include_hidden: true,
            })?;
            let has_more = rows.len() > limit as usize;
            rows.truncate(limit as usize);
            let next_before = has_more.then(|| rows.last().expect("nonempty page").seq);
            let items = rows
                .into_iter()
                .map(|row| message_view(&files, row))
                .collect();
            let progress = store.progress()?;
            let mut previews = Vec::with_capacity(chats.len());
            for chat in &chats {
                let last = store.history(HistoryQuery {
                    chat: Some(&chat.id),
                    cursor: i64::MAX,
                    limit: 1,
                    after: false,
                    files: false,
                    include_hidden: true,
                })?;
                if let Some(entry) = last.into_iter().next() {
                    let text = match (&entry.item.kind, &entry.item.text) {
                        (Kind::Text, Some(text)) => Some(preview_text(text, 120).0.to_owned()),
                        _ => None,
                    };
                    previews.push(ChatPreview {
                        chat_id: chat.id.clone(),
                        ts: entry.item.ts,
                        direction: entry.direction,
                        kind: entry.item.kind,
                        text,
                        name: entry.item.name,
                    });
                }
            }
            print_json(&SnapshotView {
                status,
                chats,
                chat_id: target_chat,
                history: HistoryPage { items, next_before },
                progress,
                previews,
            })?;
        }
        Command::TransferControl {
            direction,
            id,
            action,
        } => {
            ensure!(valid_id(&id), "invalid transfer ID");
            let peer = store.active_peer()?.context("pair with Android first")?;
            let transfer = store
                .control_transfer(&peer, direction, &id, action)?
                .context("transfer not found")?;
            if transfer.state == TransferState::Canceled {
                files.discard_transfer(&peer, direction, &transfer.item);
            }
            server::wake_daemon(&data);
            print_json(&TransferResponse {
                state: transfer.state,
            })?;
        }
        Command::Progress { json } => {
            let progress = store.progress()?;
            if json {
                print_json(&progress)?;
            } else {
                for transfer in progress {
                    println!(
                        "{}  {}  {}/{}",
                        transfer.id, transfer.direction, transfer.transferred, transfer.total
                    );
                }
            }
        }
        Command::MessageText { id } => {
            let _ = store.purge_expired(&files);
            let entry = store.message(&id)?.context("message not found")?;
            ensure!(entry.item.kind == Kind::Text, "message has no text body");
            let mut output = io::stdout().lock();
            if let Some(text) = entry.item.text {
                output.write_all(text.as_bytes())?;
            } else {
                let mut input = File::open(text_path(&files, &entry))?;
                io::copy(&mut input, &mut output)?;
            }
        }
        Command::DeleteMessage { delete_files, ids } => {
            for id in &ids {
                ensure!(valid_id(id), "invalid message ID: {id}");
            }
            let deleted = store.delete_messages(&ids, &files, delete_files)?;
            ensure!(deleted > 0, "message not found");
            server::wake_daemon(&data);
        }
        Command::ReceiveFolder => {
            let dir = files.receive_dir();
            private_directory(&dir)?;
            println!("{}", dir.display());
        }
        Command::ClearHistory => {
            let count = store.clear_history()?;
            println!("Cleared {count} received file entries.");
        }
        Command::Settings {
            stdin_json,
            message_notify,
            message_sound,
            file_notify,
            file_sound,
            connected_notify,
            connected_sound,
            save_dir,
            reset_save_dir,
        } => {
            let mut settings = if stdin_json {
                let mut raw = String::new();
                io::Read::read_to_string(&mut io::stdin(), &mut raw)?;
                serde_json::from_str::<model::NotificationSettings>(&raw)
                    .context("invalid settings JSON")?
            } else {
                model::load_notification_settings(&data)
            };
            let mut changed = stdin_json;
            if let Some(v) = message_notify {
                settings.message_notify = v;
                changed = true;
            }
            if let Some(v) = message_sound {
                settings.message_sound = v;
                changed = true;
            }
            if let Some(v) = file_notify {
                settings.file_notify = v;
                changed = true;
            }
            if let Some(v) = file_sound {
                settings.file_sound = v;
                changed = true;
            }
            if let Some(v) = connected_notify {
                settings.connected_notify = v;
                changed = true;
            }
            if let Some(v) = connected_sound {
                settings.connected_sound = v;
                changed = true;
            }
            if reset_save_dir {
                settings.receive_dir = None;
                changed = true;
            } else if let Some(dir) = save_dir {
                let trimmed = dir.trim();
                settings.receive_dir = if trimmed.is_empty() {
                    None
                } else {
                    Some(std::path::absolute(trimmed)?.to_string_lossy().into_owned())
                };
                changed = true;
            }
            if let Some(dir) = settings.receive_dir.as_deref() {
                let trimmed = dir.trim();
                if trimmed.is_empty() {
                    settings.receive_dir = None;
                } else {
                    let abs = std::path::absolute(trimmed)?;
                    private_directory(&abs)?;
                    settings.receive_dir = Some(abs.to_string_lossy().into_owned());
                }
            }
            if changed {
                model::save_notification_settings(&data, &settings)?;
            }
            print_json(&SettingsView {
                settings,
                effective_receive_dir: files.receive_dir().to_string_lossy().into_owned(),
                default_receive_dir: files.default_receive_dir().to_string_lossy().into_owned(),
            })?;
        }
        Command::TestSound { kind } => {
            let sound_kind = match kind.to_ascii_lowercase().as_str() {
                "message" => sound::SoundKind::Message,
                "file" => sound::SoundKind::File,
                "connected" => sound::SoundKind::Connected,
                other => bail!("unknown sound kind: {other} (use message, file, or connected)"),
            };
            sound::play_sync(&data, sound_kind);
        }
    }
    Ok(())
}

#[cfg(unix)]
fn open_lock_file(path: &std::path::Path) -> io::Result<File> {
    use std::os::unix::fs::OpenOptionsExt;
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .open(path)
}

#[cfg(not(unix))]
fn open_lock_file(path: &std::path::Path) -> io::Result<File> {
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .open(path)
}

fn queue(
    store: &Store,
    files: &Files,
    peer: &store::Peer,
    mut item: Item,
    chat: Option<String>,
    reply_to: Option<String>,
    ttl: Option<u64>,
) -> Result<()> {
    item.chat_id = chat;
    item.reply_to = reply_to;
    item.ttl_sec = ttl;
    if let Err(error) = store.queue(peer, &item) {
        if item.payload().is_some() {
            files.remove_outgoing(&item);
        }
        return Err(error);
    }
    println!("Queued {}", item.id);
    Ok(())
}

#[derive(Serialize)]
struct SettingsView {
    #[serde(flatten)]
    settings: model::NotificationSettings,
    effective_receive_dir: String,
    default_receive_dir: String,
}

#[derive(Serialize)]
struct SnapshotView {
    status: model::StatusInfo,
    chats: Vec<Chat>,
    chat_id: String,
    history: HistoryPage,
    progress: Vec<store::Progress>,
    previews: Vec<ChatPreview>,
}

#[derive(Serialize)]
struct ChatPreview {
    chat_id: String,
    ts: i64,
    direction: String,
    kind: Kind,
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
}

#[derive(Serialize)]
struct HistoryPage {
    items: Vec<MessageView>,
    next_before: Option<i64>,
}

#[derive(Serialize)]
struct MessageView {
    seq: i64,
    id: String,
    kind: Kind,
    ts: i64,
    chat_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    reply_to: Option<String>,
    direction: String,
    delivered: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    transfer_state: Option<TransferState>,
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<String>,
    text_truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    size: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    path: Option<PathBuf>,
    #[serde(skip_serializing_if = "Option::is_none")]
    expires_at: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ttl_sec: Option<u64>,
}

fn message_view(files: &Files, entry: HistoryEntry) -> MessageView {
    let (text, text_truncated) = if entry.item.kind == Kind::Text {
        let preview = match &entry.item.text {
            Some(text) => {
                let (preview, truncated) = preview_text(text, 384);
                Some((preview.to_owned(), truncated))
            }
            None => preview_file(&text_path(files, &entry), 384).ok(),
        };
        let (text, truncated) = preview.unwrap_or_else(|| (String::new(), true));
        (Some(text), truncated)
    } else {
        (None, false)
    };
    let path = entry
        .saved_name
        .as_deref()
        .filter(|_| entry.delivered)
        .map(|name| files.received_file(name))
        .filter(|path| path.is_file());
    let chat_id = entry.item.chat_id().to_owned();
    MessageView {
        seq: entry.seq,
        id: entry.item.id,
        kind: entry.item.kind,
        ts: entry.item.ts,
        chat_id,
        reply_to: entry.item.reply_to,
        direction: entry.direction,
        delivered: entry.delivered,
        transfer_state: entry.transfer_state,
        text,
        text_truncated,
        name: entry.item.name,
        size: entry.item.size.or(entry.item.text_size),
        path,
        expires_at: entry.expires_at,
        ttl_sec: entry.item.ttl_sec,
    }
}

fn text_path(files: &Files, entry: &HistoryEntry) -> PathBuf {
    if entry.direction == "received" {
        files.received_text(&entry.peer, &entry.item.id)
    } else {
        files.sent_text(&entry.item.id)
    }
}

fn media_type(name: &str) -> Option<&'static str> {
    let extension = name.rsplit_once('.')?.1.to_ascii_lowercase();
    Some(match extension.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "avif" => "image/avif",
        "bmp" => "image/bmp",
        "heic" | "heif" => "image/heif",
        "mp4" | "m4v" => "video/mp4",
        "webm" => "video/webm",
        "mov" => "video/quicktime",
        "mkv" => "video/x-matroska",
        "avi" => "video/x-msvideo",
        "3gp" => "video/3gpp",
        _ => return None,
    })
}

fn text_file_contains(path: &std::path::Path, needle: &[u8]) -> bool {
    use std::io::Read;
    let Ok(mut file) = File::open(path) else {
        return false;
    };
    let mut buffer = [0; 8192];
    let mut window = Vec::with_capacity(buffer.len() + needle.len());
    while let Ok(count @ 1..) = file.read(&mut buffer) {
        window.extend_from_slice(&buffer[..count]);
        window.make_ascii_lowercase();
        if window.windows(needle.len()).any(|part| part == needle) {
            return true;
        }
        let keep = (needle.len() - 1).min(window.len());
        window.drain(..window.len() - keep);
    }
    false
}

fn print_message(message: &MessageView) {
    let status = if message.delivered {
        "delivered"
    } else {
        "queued"
    };
    let label = message
        .text
        .as_deref()
        .or(message.name.as_deref())
        .unwrap_or_default();
    println!(
        "{}  {}  {}  {}  {}",
        message.ts,
        message.direction,
        status,
        message.kind.as_str(),
        label.escape_debug()
    );
}

fn print_json(data: &impl Serialize) -> Result<()> {
    let mut output = io::stdout().lock();
    serde_json::to_writer(&mut output, data)?;
    output.write_all(b"\n")?;
    Ok(())
}
