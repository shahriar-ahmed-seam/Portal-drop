use anyhow::{Result, ensure};
use chrono::{Local, TimeZone};
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

pub const PORT: u16 = 53621;
pub const PROTOCOL_VERSION: u32 = 5;
pub const FOLDER_PROTOCOL_VERSION: u32 = 5;
pub const TEMP_TTL_SEC: u64 = 600;
pub const MIN_TTL_SEC: u64 = 10;
pub const MAX_TTL_SEC: u64 = 86_400;
pub const MAX_INLINE_TEXT: usize = 16 * 1024;
pub const MAX_NAME: usize = 240;
pub const MAX_FILE_SIZE: u64 = i64::MAX as u64;
pub const DEFAULT_CHAT: &str = "00000000-0000-0000-0000-000000000001";

#[derive(Clone, Copy, Debug, clap::ValueEnum)]
pub enum TransferDirection {
    Sent,
    Received,
}

impl TransferDirection {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Sent => "sent",
            Self::Received => "received",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "sent" => Some(Self::Sent),
            "received" => Some(Self::Received),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum TransferState {
    Queued,
    Paused,
    Canceled,
    Failed,
    Delivered,
}

impl TransferState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Paused => "paused",
            Self::Canceled => "canceled",
            Self::Failed => "failed",
            Self::Delivered => "delivered",
        }
    }

    pub fn from_database(value: Option<&str>, done: bool) -> Self {
        if done {
            return Self::Delivered;
        }
        match value {
            Some("paused") => Self::Paused,
            Some("canceled") => Self::Canceled,
            Some("failed") => Self::Failed,
            _ => Self::Queued,
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, clap::ValueEnum)]
#[serde(rename_all = "lowercase")]
pub enum TransferAction {
    Pause,
    Resume,
    Cancel,
    Retry,
}

impl TransferAction {
    pub fn apply(self, state: TransferState) -> TransferState {
        if matches!(state, TransferState::Delivered | TransferState::Canceled) {
            return state;
        }
        match self {
            Self::Pause => TransferState::Paused,
            Self::Resume | Self::Retry => TransferState::Queued,
            Self::Cancel => TransferState::Canceled,
        }
    }
}

#[derive(Deserialize)]
pub struct TransferCommand {
    pub action: TransferAction,
}

#[derive(Serialize)]
pub struct TransferResponse {
    pub state: TransferState,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Item {
    pub id: String,
    pub kind: Kind,
    pub ts: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub relative_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chat_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reply_to: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text_size: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text_sha256: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ttl_sec: Option<u64>,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Text,
    File,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Text => "text",
            Self::File => "file",
        }
    }
}

impl Item {
    fn outgoing(id: String, kind: Kind) -> Self {
        Self {
            id,
            kind,
            ts: now_ms(),
            text: None,
            name: None,
            relative_path: None,
            size: None,
            sha256: None,
            chat_id: None,
            reply_to: None,
            text_size: None,
            text_sha256: None,
            ttl_sec: None,
        }
    }

    pub fn outgoing_text(id: String, text: String) -> Self {
        Self {
            text: Some(text),
            ..Self::outgoing(id, Kind::Text)
        }
    }

    pub fn outgoing_long_text(id: String, size: u64, sha256: String) -> Self {
        Self {
            text_size: Some(size),
            text_sha256: Some(sha256),
            ..Self::outgoing(id, Kind::Text)
        }
    }

    pub fn outgoing_file(id: String, name: String, size: u64, sha256: String) -> Self {
        Self {
            name: Some(name),
            size: Some(size),
            sha256: Some(sha256),
            ..Self::outgoing(id, Kind::File)
        }
    }

    pub fn chat_id(&self) -> &str {
        self.chat_id.as_deref().unwrap_or(DEFAULT_CHAT)
    }

    pub fn payload(&self) -> Option<(u64, &str)> {
        match self.kind {
            Kind::File => self.size.zip(self.sha256.as_deref()),
            Kind::Text => self.text_size.zip(self.text_sha256.as_deref()),
        }
    }

    pub fn validate(&self) -> Result<()> {
        ensure!(valid_id(&self.id), "invalid item ID");
        ensure!(self.ts >= 0, "invalid timestamp");
        ensure!(
            self.chat_id.as_deref().is_none_or(valid_id),
            "invalid chat ID"
        );
        ensure!(
            self.reply_to.as_deref().is_none_or(valid_id),
            "invalid reply ID"
        );
        ensure!(
            self.ttl_sec
                .is_none_or(|ttl| (MIN_TTL_SEC..=MAX_TTL_SEC).contains(&ttl)),
            "invalid temporary message lifetime"
        );

        match self.kind {
            Kind::Text => {
                ensure!(
                    self.name.is_none()
                        && self.size.is_none()
                        && self.sha256.is_none()
                        && self.relative_path.is_none(),
                    "unexpected file metadata"
                );
                if let Some(text) = &self.text {
                    ensure!(
                        !text.trim().is_empty() && text.len() <= MAX_INLINE_TEXT,
                        "invalid inline text length"
                    );
                    ensure!(
                        self.text_size.is_none() && self.text_sha256.is_none(),
                        "unexpected text payload"
                    );
                } else {
                    ensure!(
                        self.text_size
                            .is_some_and(|size| (1..=MAX_FILE_SIZE).contains(&size)),
                        "invalid text size"
                    );
                    ensure!(
                        self.text_sha256.as_deref().is_some_and(is_hex_secret),
                        "invalid text hash"
                    );
                }
            }
            Kind::File => {
                ensure!(
                    self.text.is_none() && self.text_size.is_none() && self.text_sha256.is_none(),
                    "unexpected file text"
                );
                let name = self.name.as_deref().unwrap_or_default();
                ensure!(
                    !name.trim().is_empty() && name.len() <= MAX_NAME,
                    "invalid filename"
                );
                if let Some(path) = &self.relative_path {
                    crate::folders::validate(path, name)?;
                }
                ensure!(
                    self.size.is_some_and(|size| size <= MAX_FILE_SIZE),
                    "invalid file size"
                );
                ensure!(
                    self.sha256.as_deref().is_some_and(is_hex_secret),
                    "invalid file hash"
                );
            }
        }
        Ok(())
    }
}

fn is_false(value: &bool) -> bool {
    !*value
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Chat {
    pub id: String,
    pub title: String,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default, skip_serializing_if = "is_false")]
    pub deleted: bool,
}

impl Chat {
    pub fn new(title: Option<String>) -> Result<Self> {
        let now = now_ms();
        let chat = Self {
            id: Uuid::new_v4().to_string(),
            title: title.unwrap_or_else(|| date_title(now)),
            created_at: now,
            updated_at: now,
            deleted: false,
        };
        chat.validate()?;
        Ok(chat)
    }

    pub fn validate(&self) -> Result<()> {
        ensure!(valid_id(&self.id), "invalid chat ID");
        ensure!(
            !self.title.trim().is_empty()
                && self.title.len() <= MAX_NAME
                && !self.title.chars().any(char::is_control),
            "chat title must be between 1 and 240 UTF-8 bytes"
        );
        ensure!(
            self.created_at >= 0 && self.updated_at >= 0,
            "invalid chat timestamp"
        );
        Ok(())
    }
}

pub fn date_title(ts: i64) -> String {
    Local
        .timestamp_millis_opt(ts)
        .single()
        .unwrap_or_else(Local::now)
        .format("%Y-%m-%d")
        .to_string()
}

#[derive(Deserialize)]
pub struct PairRequest {
    pub code: String,
    pub token: String,
    #[serde(default)]
    pub device_os: Option<String>,
    #[serde(default)]
    pub device_name: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct DeviceInfo {
    pub os: String,
    pub name: String,
    pub protocol_version: u32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct StatusInfo {
    pub paired: bool,
    pub connected: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub connected_local_ips: Vec<std::net::IpAddr>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer_os: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer_name: Option<String>,
    pub local_os: String,
    pub local_name: String,
}

#[derive(Deserialize)]
pub struct Offset {
    #[serde(default)]
    pub offset: u64,
}

#[derive(Serialize)]
pub struct Receipt {
    pub received: bool,
    pub offset: u64,
}

fn default_true() -> bool {
    true
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationSettings {
    #[serde(default = "default_true")]
    pub message_notify: bool,
    #[serde(default = "default_true")]
    pub message_sound: bool,
    #[serde(default = "default_true")]
    pub file_notify: bool,
    #[serde(default = "default_true")]
    pub file_sound: bool,
    #[serde(default = "default_true")]
    pub connected_notify: bool,
    #[serde(default = "default_true")]
    pub connected_sound: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub receive_dir: Option<String>,
}

impl Default for NotificationSettings {
    fn default() -> Self {
        Self {
            message_notify: true,
            message_sound: true,
            file_notify: true,
            file_sound: true,
            connected_notify: true,
            connected_sound: true,
            receive_dir: None,
        }
    }
}

pub fn load_notification_settings(data_dir: &std::path::Path) -> NotificationSettings {
    let path = data_dir.join("settings.json");
    std::fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

pub fn save_notification_settings(
    data_dir: &std::path::Path,
    settings: &NotificationSettings,
) -> Result<()> {
    let path = data_dir.join("settings.json");
    let tmp = data_dir.join("settings.json.tmp");
    let body = serde_json::to_string_pretty(settings)?;
    #[cfg(unix)]
    {
        use std::{io::Write, os::unix::fs::OpenOptionsExt};
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&tmp)?;
        file.write_all(body.as_bytes())?;
        file.write_all(b"\n")?;
    }
    #[cfg(not(unix))]
    {
        std::fs::write(&tmp, format!("{body}\n"))?;
    }
    std::fs::rename(&tmp, &path)?;
    Ok(())
}

pub fn local_os() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        std::env::consts::OS
    }
}

pub fn local_name() -> String {
    let raw = std::env::var("PORTAL_DROP_DEVICE_NAME")
        .ok()
        .or_else(|| std::env::var("COMPUTERNAME").ok())
        .or_else(|| std::env::var("HOSTNAME").ok())
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok());
    raw.as_deref()
        .and_then(sanitize_device_label)
        .unwrap_or_else(|| match local_os() {
            "windows" => "Windows PC".to_owned(),
            "linux" => "Linux PC".to_owned(),
            "macos" => "Mac".to_owned(),
            _ => "Desktop".to_owned(),
        })
}

pub fn sanitize_device_label(raw: &str) -> Option<String> {
    let cleaned: String = raw
        .trim()
        .chars()
        .filter(|ch| ch.is_ascii() && (' '..='~').contains(ch))
        .take(64)
        .collect();
    let trimmed = cleaned.trim();
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

pub fn sanitize_os(raw: &str) -> Option<String> {
    let cleaned: String = raw
        .trim()
        .to_ascii_lowercase()
        .chars()
        .filter(|ch| ch.is_ascii_alphanumeric() || *ch == '-' || *ch == '_')
        .take(24)
        .collect();
    (!cleaned.is_empty()).then_some(cleaned)
}

pub fn format_peer_label(peer_os: Option<&str>, peer_name: Option<&str>) -> String {
    let os_label = match peer_os {
        Some("android") => "Android",
        Some("windows") => "Windows PC",
        Some("linux") => "Linux PC",
        Some("macos") => "Mac",
        Some(other) if !other.is_empty() => other,
        _ => "Android",
    };
    match peer_name {
        Some(name) if !name.eq_ignore_ascii_case(os_label) => format!("{name} ({os_label})"),
        _ => os_label.to_owned(),
    }
}

pub fn valid_id(id: &str) -> bool {
    Uuid::parse_str(id).is_ok_and(|uuid| uuid.to_string() == id)
}

pub fn is_hex_secret(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

pub fn random_secret() -> Result<String> {
    let mut bytes = [0_u8; 32];
    getrandom::fill(&mut bytes).map_err(|error| anyhow::anyhow!("secure randomness: {error}"))?;
    Ok(hex::encode(bytes))
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
