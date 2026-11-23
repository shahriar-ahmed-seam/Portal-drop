mod files;
mod model;
mod server;
mod sound;
mod store;

use anyhow::{Context, Result, bail, ensure};
use clap::{Parser, Subcommand};
use files::{Files, preview_file, preview_text};
use fs2::FileExt;
use model::{Chat, Item, Kind, MAX_TTL_SEC, MIN_TTL_SEC, PORT, TEMP_TTL_SEC, valid_id};
use qrcode::{QrCode, render::unicode};
use serde::Serialize;
use std::{
    fs::{self, File, OpenOptions},
    io::{self, IsTerminal, Write},
    net::{SocketAddr, UdpSocket},
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
}

fn main() -> Result<()> {
    let args = Args::parse();
    let data = match args.data_dir {
        Some(path) => std::path::absolute(path)?,
        None => dirs::data_dir()
            .context("set --data-dir for this desktop")?
            .join("portal-drop"),
    };
    let receive = match args.receive_dir {
        Some(path) => std::path::absolute(path)?,
        None => dirs::download_dir()
            .or_else(|| dirs::home_dir().map(|home| home.join("Downloads")))
            .context("set --receive-dir for this desktop")?
            .join("Portal Drop"),
    };
    let store = Store::initialize(&data)?;
    let files = Files::new(&data, receive);
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
            let address = pairing_address(address)?;
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
        Command::Unpair => {
            store.unpair()?;
            server::wake_daemon(&data);
            println!("Android device unpaired.");
        }
        Command::Status { json } => {
            let status = store.status_info()?;
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
            let status = store.status_info()?;
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
            private_directory(files.receive_dir())?;
            println!("{}", files.receive_dir().display());
        }
        Command::ClearHistory => {
            let count = store.clear_history()?;
            println!("Cleared {count} received file entries.");
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
            let _ = fs::remove_file(files.outgoing_payload(&item));
        }
        return Err(error);
    }
    println!("Queued {}", item.id);
    Ok(())
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

fn pairing_address(hint: Option<String>) -> Result<String> {
    let hint = match hint {
        Some(value) => value,
        None => {
            let socket = UdpSocket::bind("0.0.0.0:0")?;
            // UDP connect selects an interface without sending a packet.
            socket
                .connect("192.0.2.1:9")
                .context("set --address to the desktop's LAN address")?;
            socket.local_addr()?.ip().to_string()
        }
    };
    let (host, port) = match hint.split_once(':') {
        Some((host, port)) => (host, port.parse::<u16>().context("invalid --address port")?),
        None => (hint.as_str(), PORT),
    };
    if host.is_empty()
        || host.len() > 253
        || port == 0
        || !host
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-'))
    {
        bail!("use an IPv4 address or hostname, optionally followed by :PORT");
    }
    ensure!(
        !host.starts_with('.') && !host.ends_with('.'),
        "invalid --address hostname"
    );
    Ok(format!("{host}:{port}"))
}
