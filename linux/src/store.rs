use crate::{
    files::Files,
    model::{
        Chat, DEFAULT_CHAT, Item, Kind, StatusInfo, TransferAction, TransferDirection,
        TransferState, date_title, is_hex_secret, local_name, local_os, now_ms, random_secret,
        sanitize_device_label, sanitize_os, valid_id,
    },
};
use anyhow::{Context, Result, ensure};
use rcgen::generate_simple_self_signed;
use rusqlite::{Connection, OptionalExtension, TransactionBehavior::Immediate, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, DirBuilder, OpenOptions},
    path::{Path, PathBuf},
    time::Duration,
};
use subtle::ConstantTimeEq;

const INVITE_LIFETIME_MS: i64 = 10 * 60 * 1_000;
const PRESENCE_STALE_MS: i64 = 65_000;
pub type Peer = [u8; 32];

#[derive(Clone)]
pub struct Store {
    path: PathBuf,
}

pub struct Identity {
    pub cert: Vec<u8>,
    pub key: Vec<u8>,
    pub fingerprint: String,
}

pub struct Incoming {
    pub item: Item,
    pub saved_name: Option<String>,
    pub received: bool,
}

pub struct HistoryEntry {
    pub seq: i64,
    pub item: Item,
    pub direction: String,
    pub delivered: bool,
    pub saved_name: Option<String>,
    pub peer: Peer,
    pub expires_at: Option<i64>,
    pub transfer_state: Option<TransferState>,
}

pub struct Transfer {
    pub item: Item,
    pub state: TransferState,
}

#[derive(Serialize, Deserialize)]
pub struct Progress {
    pub id: String,
    pub chat_id: String,
    pub kind: Kind,
    pub name: Option<String>,
    pub direction: String,
    pub offset: u64,
    pub transferred: u64,
    pub total: u64,
    #[serde(default)]
    pub started_at: i64,
    pub updated_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transfer_state: Option<TransferState>,
}

pub struct HistoryQuery<'a> {
    pub chat: Option<&'a str>,
    pub cursor: i64,
    pub limit: u32,
    pub after: bool,
    pub files: bool,
    pub include_hidden: bool,
}

impl Store {
    pub fn initialize(directory: &Path) -> Result<Self> {
        private_directory(directory)?;
        let store = Self {
            path: directory.join("portal-drop.sqlite3"),
        };
        open_private_database_file(&store.path).context("open private Portal Drop database")?;
        let mut connection = store.connect()?;
        if version(&connection)? < 3 {
            connection.pragma_update(None, "journal_mode", "WAL")?;
            let transaction = connection.transaction_with_behavior(Immediate)?;
            let current = version(&transaction)?;
            if current == 0 {
                migrate_v1(&transaction)?;
            }
            if current < 2 {
                migrate_v2(&transaction)?;
            }
            if current < 3 {
                migrate_v3(&transaction)?;
            }
            transaction.commit()?;
        }
        Ok(store)
    }

    fn connect(&self) -> Result<Connection> {
        let connection = Connection::open(&self.path)?;
        connection.busy_timeout(Duration::from_secs(3))?;
        connection.pragma_update(None, "synchronous", "NORMAL")?;
        Ok(connection)
    }

    pub fn wake_revision(&self) -> Result<i64> {
        let connection = self.connect()?;
        Ok(connection.query_row("SELECT revision FROM wake_revision", [], |row| row.get(0))?)
    }

    pub fn identity(&self) -> Result<Identity> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        let identity = transaction
            .query_row("SELECT cert, key, fp FROM identity", [], |row| {
                Ok(Identity {
                    cert: row.get(0)?,
                    key: row.get(1)?,
                    fingerprint: row.get(2)?,
                })
            })
            .optional()?;

        if let Some(identity) = identity {
            transaction.commit()?;
            return Ok(identity);
        }

        let generated = generate_simple_self_signed(vec!["portaldrop.local".into()])?;
        let identity = Identity {
            cert: generated.cert.pem().into_bytes(),
            key: generated.signing_key.serialize_pem().into_bytes(),
            fingerprint: hex::encode(Sha256::digest(generated.cert.der())),
        };
        transaction.execute(
            "INSERT INTO identity VALUES (1, ?1, ?2, ?3)",
            params![identity.cert, identity.key, identity.fingerprint],
        )?;
        transaction.commit()?;
        Ok(identity)
    }

    pub fn issue_invite(&self) -> Result<String> {
        let code = random_secret()?;
        let invite = digest(&code);
        let affected = self.connect()?.execute(
            "UPDATE trust SET invite = ?1, expires_at = ?2 WHERE peer IS NULL",
            params![invite, now_ms() + INVITE_LIFETIME_MS],
        )?;
        ensure!(
            affected == 1,
            "this computer is already paired; run unpair first"
        );
        Ok(code)
    }

    pub fn pair(
        &self,
        code: &str,
        token: &str,
        device_os: Option<&str>,
        device_name: Option<&str>,
    ) -> Result<bool> {
        if !is_hex_secret(code) || !is_hex_secret(token) {
            return Ok(false);
        }

        let peer_os = device_os.and_then(sanitize_os);
        let peer_name = device_name.and_then(sanitize_device_label);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        let (peer, invite, expires): (Option<Peer>, Option<Peer>, Option<i64>) = transaction
            .query_row("SELECT peer, invite, expires_at FROM trust", [], |row| {
                Ok((row.get(0)?, row.get(1)?, row.get(2)?))
            })?;
        let candidate = digest(token);

        if let Some(peer) = peer {
            let matched = bool::from(peer.ct_eq(&candidate));
            if matched && (peer_os.is_some() || peer_name.is_some()) {
                transaction.execute(
                    "UPDATE trust SET peer_os = coalesce(?1, peer_os), peer_name = coalesce(?2, peer_name)",
                    params![peer_os, peer_name],
                )?;
                transaction.commit()?;
            }
            return Ok(matched);
        }

        let valid = invite.zip(expires).is_some_and(|(invite, expires)| {
            bool::from(invite.ct_eq(&digest(code))) && expires > now_ms()
        });
        if !valid {
            return Ok(false);
        }

        transaction.execute(
            "UPDATE trust SET peer = ?1, invite = NULL, expires_at = NULL,
                              peer_os = coalesce(?2, 'android'), peer_name = ?3,
                              connected = 0, last_seen = ?4",
            params![candidate, peer_os, peer_name, now_ms()],
        )?;
        transaction.commit()?;
        Ok(true)
    }

    pub fn update_peer_meta(
        &self,
        peer: &Peer,
        peer_os: Option<&str>,
        peer_name: Option<&str>,
    ) -> Result<()> {
        let os = peer_os.and_then(sanitize_os);
        let name = peer_name.and_then(sanitize_device_label);
        if os.is_none() && name.is_none() {
            return Ok(());
        }
        self.connect()?.execute(
            "UPDATE trust
             SET peer_os = coalesce(?2, peer_os),
                 peer_name = coalesce(?3, peer_name)
             WHERE peer = ?1
               AND ((?2 IS NOT NULL AND coalesce(peer_os, '') != ?2)
                    OR (?3 IS NOT NULL AND coalesce(peer_name, '') != ?3))",
            params![peer, os, name],
        )?;
        Ok(())
    }

    pub fn set_connected(&self, connected: bool) -> Result<()> {
        let now = now_ms();
        if connected {
            self.connect()?.execute(
                "UPDATE trust SET connected = 1, last_seen = ?1 WHERE peer IS NOT NULL",
                params![now],
            )?;
        } else {
            self.connect()?
                .execute("UPDATE trust SET connected = 0", [])?;
        }
        Ok(())
    }

    pub fn touch_last_seen(&self, peer: &Peer) -> Result<()> {
        self.connect()?.execute(
            "UPDATE trust SET last_seen = ?2 WHERE peer = ?1",
            params![peer, now_ms()],
        )?;
        Ok(())
    }

    pub fn peer_identity(&self) -> Result<(Option<String>, Option<String>)> {
        let connection = self.connect()?;
        Ok(
            connection.query_row("SELECT peer_os, peer_name FROM trust", [], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })?,
        )
    }

    pub fn status_info(&self) -> Result<StatusInfo> {
        let connection = self.connect()?;
        let (peer, peer_os, peer_name, connected, last_seen): (
            Option<Peer>,
            Option<String>,
            Option<String>,
            i64,
            Option<i64>,
        ) = connection.query_row(
            "SELECT peer, peer_os, peer_name, connected, last_seen FROM trust",
            [],
            |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                ))
            },
        )?;
        let paired = peer.is_some();
        let fresh =
            last_seen.is_some_and(|seen| now_ms().saturating_sub(seen) <= PRESENCE_STALE_MS);
        Ok(StatusInfo {
            paired,
            connected: paired && connected != 0 && fresh,
            connected_local_ips: Vec::new(),
            connected_local_routes: Default::default(),
            peer_os: if paired { peer_os } else { None },
            peer_name: if paired { peer_name } else { None },
            local_os: local_os().to_owned(),
            local_name: local_name(),
        })
    }

    pub fn active_peer(&self) -> Result<Option<Peer>> {
        let connection = self.connect()?;
        active_peer(&connection)
    }

    pub fn authenticate(&self, token: &str) -> Result<Option<Peer>> {
        if !is_hex_secret(token) {
            return Ok(None);
        }
        let hash = digest(token);
        Ok(self
            .active_peer()?
            .filter(|peer| bool::from(peer.ct_eq(&hash))))
    }

    pub fn unpair(&self) -> Result<()> {
        self.connect()?.execute(
            "UPDATE trust SET peer = NULL, invite = NULL, expires_at = NULL,
                              peer_os = NULL, peer_name = NULL, connected = 0, last_seen = NULL",
            [],
        )?;
        Ok(())
    }

    pub fn queue(&self, peer: &Peer, item: &Item) -> Result<()> {
        self.queue_batch(peer, std::slice::from_ref(item))
    }

    pub fn queue_batch(&self, peer: &Peer, items: &[Item]) -> Result<()> {
        for item in items {
            item.validate()?;
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        ensure!(
            active_peer(&transaction)? == Some(*peer),
            "pairing changed while preparing item"
        );
        {
            let mut statement = transaction.prepare(
                "INSERT INTO outbox (id, peer, item, ts, expires_at) VALUES (?1, ?2, ?3, ?4, ?5)",
            )?;
            for item in items {
                ensure!(
                    active_chat_exists(&transaction, item.chat_id())?,
                    "chat not found"
                );
                let encoded = serde_json::to_string(item)?;
                let expires_at = item
                    .ttl_sec
                    .map(|ttl| now_ms().saturating_add((ttl as i64).saturating_mul(1_000)));
                statement.execute(params![item.id, peer, encoded, item.ts, expires_at])?;
            }
        }
        transaction.commit()?;
        Ok(())
    }

    pub fn outbox(&self, peer: &Peer, protocol_version: u32) -> Result<Vec<Item>> {
        let connection = self.connect()?;
        let now = now_ms();
        let modern = protocol_version >= 2;
        let supports_ttl = protocol_version >= 3;
        let supports_folders = protocol_version >= crate::model::FOLDER_PROTOCOL_VERSION;
        let mut stmt = connection.prepare(
            "SELECT item FROM outbox
             WHERE peer = ?1
               AND delivered = 0
               AND NOT EXISTS (
                   SELECT 1 FROM transfer_controls AS control
                   WHERE control.id = outbox.id AND control.peer = outbox.peer
                     AND control.direction = 'sent' AND control.state != 'queued'
               )
               AND (expires_at IS NULL OR expires_at > ?4)
               AND (?2 OR (json_extract(item, '$.text_size') IS NULL
                           AND coalesce(json_extract(item, '$.chat_id'), ?3) = ?3))
               AND (?5 OR json_extract(item, '$.ttl_sec') IS NULL)
               AND (?6 OR json_extract(item, '$.relative_path') IS NULL)
             ORDER BY CASE WHEN json_extract(item, '$.kind') = 'text'
                            AND json_extract(item, '$.text_size') IS NULL THEN 0 ELSE 1 END,
                      ts, rowid
             LIMIT 32",
        )?;
        stmt.query_map(
            params![
                peer,
                modern,
                DEFAULT_CHAT,
                now,
                supports_ttl,
                supports_folders
            ],
            |row| row.get::<_, String>(0),
        )?
        .map(|entry| {
            let mut item: Item = serde_json::from_str(&entry?)?;
            if !modern {
                item.chat_id = None;
                item.reply_to = None;
            }
            if !supports_ttl {
                item.ttl_sec = None;
            }
            Ok(item)
        })
        .collect()
    }

    pub fn acknowledge(&self, peer: &Peer, id: &str) -> Result<bool> {
        let updated = self.connect()?.execute(
            "UPDATE outbox SET delivered = 1
             WHERE id = ?1 AND peer = ?2 AND (SELECT peer FROM trust) = ?2
               AND (delivered = 1 OR NOT EXISTS (
                   SELECT 1 FROM transfer_controls
                   WHERE id = ?1 AND peer = ?2 AND direction = 'sent' AND state != 'queued'
               ))",
            params![id, peer],
        )?;
        Ok(updated == 1)
    }

    pub fn incoming(&self, peer: &Peer, id: &str) -> Result<Option<Incoming>> {
        let raw = self
            .connect()?
            .query_row(
                "SELECT item, saved_name, received FROM inbox WHERE id = ?1 AND peer = ?2",
                params![id, peer],
                |row| Ok((row.get::<_, String>(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()?;
        raw.map(|(encoded, saved_name, received)| {
            Ok(Incoming {
                item: serde_json::from_str(&encoded)?,
                saved_name,
                received,
            })
        })
        .transpose()
    }

    pub fn transfer(
        &self,
        peer: &Peer,
        direction: TransferDirection,
        id: &str,
    ) -> Result<Option<Transfer>> {
        let connection = self.connect()?;
        find_transfer(&connection, peer, direction, id)
    }

    pub fn control_transfer(
        &self,
        peer: &Peer,
        direction: TransferDirection,
        id: &str,
        action: TransferAction,
    ) -> Result<Option<Transfer>> {
        self.change_transfer(peer, direction, id, |state| action.apply(state))
    }

    pub fn fail_transfer(&self, peer: &Peer, direction: TransferDirection, id: &str) -> Result<()> {
        self.change_transfer(peer, direction, id, |state| {
            if state == TransferState::Queued {
                TransferState::Failed
            } else {
                state
            }
        })?;
        Ok(())
    }

    fn change_transfer(
        &self,
        peer: &Peer,
        direction: TransferDirection,
        id: &str,
        action: impl FnOnce(TransferState) -> TransferState,
    ) -> Result<Option<Transfer>> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        if active_peer(&transaction)? != Some(*peer) {
            return Ok(None);
        }
        let Some(mut transfer) = find_transfer(&transaction, peer, direction, id)? else {
            return Ok(None);
        };
        let state = action(transfer.state);
        if state != transfer.state {
            write_transfer_state(&transaction, peer, direction, id, state)?;
            transfer.state = state;
        }
        transaction.commit()?;
        Ok(Some(transfer))
    }

    pub fn offer(&self, peer: &Peer, item: &Item, saved_name: Option<&str>) -> Result<bool> {
        let encoded = serde_json::to_string(item)?;
        let expires_at = item
            .ttl_sec
            .map(|ttl| now_ms().saturating_add((ttl as i64).saturating_mul(1_000)));
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        if active_peer(&transaction)? != Some(*peer) {
            return Ok(false);
        }
        let title = date_title(item.ts);
        transaction.execute(
            "INSERT OR IGNORE INTO chats (id, title, created_at, updated_at, deleted)
             VALUES (?1, ?2, ?3, 0, 0)",
            params![item.chat_id(), title, item.ts],
        )?;
        transaction.execute(
            "INSERT INTO inbox (id, peer, item, ts, saved_name, received, expires_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                item.id,
                peer,
                encoded,
                item.ts,
                saved_name,
                item.text.is_some(),
                expires_at
            ],
        )?;
        transaction.commit()?;
        Ok(true)
    }

    pub fn reserved_filename(&self, name: &str) -> Result<bool> {
        let found: bool = self.connect()?.query_row(
            "SELECT EXISTS (SELECT 1 FROM inbox WHERE saved_name = ?1)",
            params![name],
            |row| row.get(0),
        )?;
        Ok(found)
    }

    pub fn finalize(
        &self,
        peer: &Peer,
        id: &str,
        changed_name: Option<&str>,
        refreshed_expires_at: Option<i64>,
        publish: impl FnOnce() -> Result<()>,
    ) -> Result<bool> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        if active_peer(&transaction)? != Some(*peer)
            || !find_transfer(&transaction, peer, TransferDirection::Received, id)?
                .is_some_and(|transfer| transfer.state == TransferState::Queued)
        {
            return Ok(false);
        }
        if let Some(name) = changed_name {
            transaction.execute(
                "UPDATE inbox SET saved_name = ?3 WHERE id = ?1 AND peer = ?2",
                params![id, peer, name],
            )?;
        }
        if let Some(expires_at) = refreshed_expires_at {
            transaction.execute(
                "UPDATE inbox SET expires_at = ?3 WHERE id = ?1 AND peer = ?2",
                params![id, peer, expires_at],
            )?;
            transaction.execute(
                "UPDATE timeline SET expires_at = ?3
                 WHERE id = ?1 AND peer = ?2 AND direction = 'received'",
                params![id, peer, expires_at],
            )?;
        }
        publish()?;
        transaction.execute(
            "UPDATE inbox SET received = 1 WHERE id = ?1 AND peer = ?2",
            params![id, peer],
        )?;
        transaction.commit()?;
        Ok(true)
    }

    pub fn chats(&self, include_deleted: bool) -> Result<Vec<Chat>> {
        let connection = self.connect()?;
        let sql = if include_deleted {
            "SELECT id, title, created_at, updated_at, deleted FROM chats ORDER BY created_at DESC, id"
        } else {
            "SELECT id, title, created_at, updated_at, deleted FROM chats WHERE deleted = 0 ORDER BY created_at DESC, id"
        };
        let mut stmt = connection.prepare(sql)?;
        Ok(stmt
            .query_map([], chat_from_row)?
            .collect::<rusqlite::Result<_>>()?)
    }

    pub fn write_chat(
        &self,
        peer: Option<&Peer>,
        chat: &Chat,
        files: Option<&Files>,
    ) -> Result<bool> {
        chat.validate()?;
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        if peer.is_some() && active_peer(&transaction)?.as_ref() != peer {
            return Ok(false);
        }
        let previously_deleted: Option<bool> = transaction
            .query_row(
                "SELECT deleted FROM chats WHERE id = ?1",
                [&chat.id],
                |row| row.get(0),
            )
            .optional()?;
        transaction.execute(
            "INSERT INTO chats (id, title, created_at, updated_at, deleted)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(id) DO UPDATE SET
                title = CASE WHEN excluded.updated_at > updated_at
                               OR (excluded.updated_at = updated_at AND excluded.title > title)
                             THEN excluded.title ELSE title END,
                deleted = CASE WHEN excluded.updated_at > updated_at THEN excluded.deleted
                               WHEN excluded.updated_at = updated_at THEN max(deleted, excluded.deleted)
                               ELSE deleted END,
                created_at = min(created_at, excluded.created_at),
                updated_at = max(updated_at, excluded.updated_at)",
            params![chat.id, chat.title, chat.created_at, chat.updated_at, chat.deleted],
        )?;
        let now_deleted: bool = transaction.query_row(
            "SELECT deleted FROM chats WHERE id = ?1",
            [&chat.id],
            |row| row.get(0),
        )?;
        let cleanup_paths = if now_deleted && previously_deleted != Some(true) {
            mark_chat_items_deleted(&transaction, &chat.id, files)?
        } else {
            Vec::new()
        };
        // Ensure at least one non-deleted chat always remains.
        let remaining: i64 =
            transaction.query_row("SELECT COUNT(*) FROM chats WHERE deleted = 0", [], |row| {
                row.get(0)
            })?;
        if remaining == 0 {
            let now = now_ms();
            let fallback = Chat {
                id: uuid::Uuid::new_v4().to_string(),
                title: date_title(now),
                created_at: now,
                updated_at: now,
                deleted: false,
            };
            transaction.execute(
                "INSERT INTO chats (id, title, created_at, updated_at, deleted) VALUES (?1, ?2, ?3, ?4, 0)",
                params![fallback.id, fallback.title, fallback.created_at, fallback.updated_at],
            )?;
        }
        transaction.commit()?;
        for path in cleanup_paths {
            let _ = fs::remove_file(path);
        }
        Ok(true)
    }

    pub fn rename_chat(&self, id: &str, title: String) -> Result<Chat> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        let chat = transaction
            .query_row(
                "SELECT id, title, created_at, updated_at, deleted FROM chats WHERE id = ?1 AND deleted = 0",
                [id],
                chat_from_row,
            )
            .optional()?
            .context("chat not found")?;
        let chat = Chat {
            title,
            updated_at: now_ms().max(chat.updated_at.saturating_add(1)),
            ..chat
        };
        chat.validate()?;
        transaction.execute(
            "UPDATE chats SET title = ?2, updated_at = ?3 WHERE id = ?1",
            params![chat.id, chat.title, chat.updated_at],
        )?;
        transaction.commit()?;
        Ok(chat)
    }

    pub fn delete_chat(&self, id: &str, files: &Files) -> Result<Chat> {
        ensure!(valid_id(id), "invalid chat ID");
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        let chat = transaction
            .query_row(
                "SELECT id, title, created_at, updated_at, deleted FROM chats WHERE id = ?1 AND deleted = 0",
                [id],
                chat_from_row,
            )
            .optional()?
            .context("chat not found")?;
        let remaining: i64 =
            transaction.query_row("SELECT COUNT(*) FROM chats WHERE deleted = 0", [], |row| {
                row.get(0)
            })?;
        ensure!(remaining > 1, "keep at least one chat");
        let deleted_chat = Chat {
            updated_at: now_ms().max(chat.updated_at.saturating_add(1)),
            deleted: true,
            ..chat
        };
        transaction.execute(
            "UPDATE chats SET deleted = 1, updated_at = ?2 WHERE id = ?1",
            params![deleted_chat.id, deleted_chat.updated_at],
        )?;
        let cleanup_paths = mark_chat_items_deleted(&transaction, &deleted_chat.id, Some(files))?;
        transaction.commit()?;
        for path in cleanup_paths {
            let _ = fs::remove_file(path);
        }
        Ok(deleted_chat)
    }

    pub fn history(&self, query: HistoryQuery) -> Result<Vec<HistoryEntry>> {
        let connection = self.connect()?;
        let now = now_ms();
        let order = if query.after {
            "t.seq > ?1 ORDER BY t.seq ASC"
        } else {
            "t.seq < ?1 ORDER BY t.seq DESC"
        };
        let chat = if query.chat.is_some() {
            "t.chat_id = ?2"
        } else {
            "?2 IS NULL"
        };
        let filter = if query.files {
            "AND t.direction = 'received' AND t.kind = 'file' AND i.received = 1 AND i.hidden = 0"
        } else if query.include_hidden {
            ""
        } else {
            "AND coalesce(i.hidden, 0) = 0"
        };
        let mut stmt = connection.prepare(&format!(
            "{HISTORY_SELECT}
             WHERE t.deleted = 0
               AND (t.expires_at IS NULL OR t.expires_at > ?4)
               AND {chat} {filter} AND {order} LIMIT ?3"
        ))?;
        stmt.query_map(
            params![query.cursor, query.chat, query.limit, now],
            history_from_row,
        )?
        .map(decode_history)
        .collect()
    }

    pub fn search_candidates(&self, query: &str, before: i64) -> Result<Vec<HistoryEntry>> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(&format!(
            "{HISTORY_SELECT}
             WHERE t.deleted = 0 AND t.seq < ?1 AND (t.expires_at IS NULL OR t.expires_at > ?2)
               AND (instr(lower(json_extract(coalesce(i.item, o.item), '$.text')), lower(?3)) > 0
                 OR instr(lower(json_extract(coalesce(i.item, o.item), '$.name')), lower(?3)) > 0
                 OR json_extract(coalesce(i.item, o.item), '$.text_size') IS NOT NULL)
             ORDER BY t.seq DESC LIMIT 50"
        ))?;
        statement
            .query_map(params![before, now_ms(), query], history_from_row)?
            .map(decode_history)
            .collect()
    }

    pub fn media_candidates(
        &self,
        chat: Option<&str>,
        before: i64,
        links: bool,
    ) -> Result<Vec<HistoryEntry>> {
        let connection = self.connect()?;
        let chat_filter = if chat.is_some() {
            "t.chat_id = ?2"
        } else {
            "?2 IS NULL"
        };
        let media_filter = if links {
            "t.kind = 'text' AND (
                instr(lower(json_extract(coalesce(i.item, o.item), '$.text')), 'http://') > 0
                OR instr(lower(json_extract(coalesce(i.item, o.item), '$.text')), 'https://') > 0
                OR json_extract(coalesce(i.item, o.item), '$.text_size') IS NOT NULL)"
        } else {
            "t.kind = 'file' AND (t.direction = 'sent' OR i.received = 1) AND coalesce(i.hidden, 0) = 0"
        };
        let mut statement = connection.prepare(&format!(
            "{HISTORY_SELECT}
             WHERE t.deleted = 0 AND t.seq < ?1 AND (t.expires_at IS NULL OR t.expires_at > ?3)
               AND {chat_filter} AND {media_filter}
             ORDER BY t.seq DESC LIMIT 80"
        ))?;
        statement
            .query_map(params![before, chat, now_ms()], history_from_row)?
            .map(decode_history)
            .collect()
    }

    pub fn message(&self, id: &str) -> Result<Option<HistoryEntry>> {
        let connection = self.connect()?;
        let now = now_ms();
        let raw = connection
            .query_row(
                &format!(
                    "{HISTORY_SELECT}
                     WHERE t.id = ?1 AND t.deleted = 0
                       AND (t.expires_at IS NULL OR t.expires_at > ?2)
                     ORDER BY t.seq DESC LIMIT 1"
                ),
                params![id, now],
                history_from_row,
            )
            .optional()?;
        raw.map(|entry| decode_history(Ok(entry))).transpose()
    }

    pub fn delete_messages(
        &self,
        ids: &[String],
        files: &Files,
        delete_received_files: bool,
    ) -> Result<usize> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(Immediate)?;
        let mut paths_to_remove = Vec::new();
        let mut deleted_count = 0usize;

        for id in ids {
            let row: Option<(i64, Peer, String, String, Option<String>)> = transaction
                .query_row(
                    "SELECT t.seq, t.peer, t.direction, t.kind, i.saved_name
                     FROM timeline AS t
                     LEFT JOIN inbox AS i ON t.direction = 'received' AND i.id = t.id AND i.peer = t.peer
                     WHERE t.id = ?1 AND t.deleted = 0
                     ORDER BY t.seq DESC LIMIT 1",
                    [id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?)),
                )
                .optional()?;
            let Some((seq, peer, direction, kind, saved_name)) = row else {
                continue;
            };
            let changed = transaction.execute(
                "UPDATE timeline SET deleted = 1 WHERE seq = ?1 AND deleted = 0",
                [seq],
            )?;
            if changed == 0 {
                continue;
            }
            deleted_count += 1;
            if direction == "sent" {
                let transfer = find_transfer(&transaction, &peer, TransferDirection::Sent, id)?;
                if let Some(transfer) = transfer {
                    if transfer.state != TransferState::Delivered {
                        write_transfer_state(
                            &transaction,
                            &peer,
                            TransferDirection::Sent,
                            id,
                            TransferState::Canceled,
                        )?;
                    }
                } else {
                    transaction.execute(
                        "UPDATE outbox SET delivered = 1 WHERE id = ?1 AND peer = ?2",
                        params![id, peer],
                    )?;
                }
                let _ = transaction.execute(
                    "DELETE FROM progress WHERE id = ?1 AND direction = 'sent'",
                    [id],
                );
                paths_to_remove.push(files.outgoing(id));
                paths_to_remove.push(files.outgoing_ref(id));
                paths_to_remove.push(files.sent_text(id));
            } else {
                if let Some(transfer) =
                    find_transfer(&transaction, &peer, TransferDirection::Received, id)?
                    && !matches!(
                        transfer.state,
                        TransferState::Delivered | TransferState::Canceled
                    )
                {
                    write_transfer_state(
                        &transaction,
                        &peer,
                        TransferDirection::Received,
                        id,
                        TransferState::Canceled,
                    )?;
                    paths_to_remove.push(files.partial(&peer, &transfer.item));
                }
                if kind == "text" {
                    paths_to_remove.push(files.received_text(&peer, id));
                }
                if delete_received_files {
                    if let Some(name) = saved_name {
                        paths_to_remove.push(files.received_file(&name));
                    }
                    let _ = transaction.execute(
                        "UPDATE inbox SET hidden = 1, saved_name = NULL WHERE id = ?1 AND peer = ?2",
                        params![id, peer],
                    );
                }
            }
        }
        transaction.commit()?;

        for path in paths_to_remove {
            let _ = fs::remove_file(path);
        }
        Ok(deleted_count)
    }

    pub fn purge_expired(&self, files: &Files) -> Result<usize> {
        let now = now_ms();
        let mut connection = self.connect()?;
        let has_expired: bool = connection.query_row(
            "SELECT EXISTS (
               SELECT 1 FROM timeline WHERE expires_at IS NOT NULL AND expires_at <= ?1 AND deleted = 0
               UNION ALL
               SELECT 1 FROM outbox WHERE expires_at IS NOT NULL AND expires_at <= ?1
               UNION ALL
               SELECT 1 FROM inbox WHERE expires_at IS NOT NULL AND expires_at <= ?1
             )",
            [now],
            |row| row.get(0),
        )?;
        if !has_expired {
            return Ok(0);
        }

        let transaction = connection.transaction_with_behavior(Immediate)?;
        let mut paths_to_remove = Vec::new();
        let mut purged = 0usize;

        // 1. Collect & scrub expired outbox rows
        let expired_outbox: Vec<(String, Peer, String)> = {
            let mut stmt = transaction.prepare(
                "SELECT id, peer, item FROM outbox WHERE expires_at IS NOT NULL AND expires_at <= ?1",
            )?;
            let rows = stmt.query_map([now], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?;
            rows.collect::<rusqlite::Result<_>>()?
        };
        for (id, peer, raw) in expired_outbox {
            if let Ok(item) = serde_json::from_str::<Item>(&raw) {
                paths_to_remove.push(files.outgoing(&id));
                paths_to_remove.push(files.outgoing_ref(&id));
                paths_to_remove.push(files.sent_text(&id));
                let scrubbed = scrub_item(&item);
                let _ = transaction.execute(
                    "UPDATE outbox SET item = ?3, delivered = 1, expires_at = NULL WHERE id = ?1 AND peer = ?2",
                    params![id, peer, serde_json::to_string(&scrubbed)?],
                );
                let _ = transaction.execute(
                    "DELETE FROM progress WHERE id = ?1 AND direction = 'sent'",
                    [&id],
                );
                purged += 1;
            }
        }

        // 2. Collect & scrub expired inbox rows (also removes temporary received files on disk)
        let expired_inbox: Vec<(String, Peer, String, Option<String>)> = {
            let mut stmt = transaction.prepare(
                "SELECT id, peer, item, saved_name FROM inbox WHERE expires_at IS NOT NULL AND expires_at <= ?1",
            )?;
            let rows = stmt.query_map([now], |row| {
                Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?))
            })?;
            rows.collect::<rusqlite::Result<_>>()?
        };
        for (id, peer, raw, saved_name) in expired_inbox {
            if let Ok(item) = serde_json::from_str::<Item>(&raw) {
                paths_to_remove.push(files.received_text(&peer, &id));
                paths_to_remove.push(files.partial_by_kind(&peer, item.kind, &id));
                if let Some(name) = saved_name {
                    paths_to_remove.push(files.received_file(&name));
                }
                let scrubbed = scrub_item(&item);
                let _ = transaction.execute(
                    "UPDATE inbox SET item = ?3, received = 1, hidden = 1, saved_name = NULL, expires_at = NULL
                     WHERE id = ?1 AND peer = ?2",
                    params![id, peer, serde_json::to_string(&scrubbed)?],
                );
                let _ = transaction.execute(
                    "DELETE FROM progress WHERE id = ?1 AND direction = 'received'",
                    [&id],
                );
                purged += 1;
            }
        }

        // 3. Hide expired timeline entries
        let timeline_changed = transaction.execute(
            "UPDATE timeline SET deleted = 1 WHERE expires_at IS NOT NULL AND expires_at <= ?1 AND deleted = 0",
            [now],
        )?;
        purged = purged.max(timeline_changed);
        transaction.commit()?;

        for path in paths_to_remove {
            let _ = fs::remove_file(path);
        }
        Ok(purged)
    }

    pub fn set_progress(&self, progress: &Progress) -> Result<()> {
        self.connect()?.execute(
            "INSERT INTO progress (id, direction, info, updated_at) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(id, direction) DO UPDATE SET info = excluded.info, updated_at = excluded.updated_at",
            params![progress.id, progress.direction, serde_json::to_string(progress)?, progress.updated_at],
        )?;
        Ok(())
    }

    pub fn clear_progress(&self, id: &str, direction: &str) -> Result<()> {
        self.connect()?.execute(
            "DELETE FROM progress WHERE id = ?1 AND direction = ?2",
            params![id, direction],
        )?;
        Ok(())
    }

    pub fn progress(&self) -> Result<Vec<Progress>> {
        let connection = self.connect()?;
        let mut stmt = connection.prepare(
            "SELECT progress.info, control.state FROM progress
             LEFT JOIN transfer_controls AS control ON control.id = progress.id
                  AND control.direction = progress.direction AND control.peer = (SELECT peer FROM trust)
             WHERE coalesce(control.state, '') != 'canceled'
               AND (progress.updated_at >= ?1 OR control.state IN ('paused', 'failed'))
             ORDER BY progress.updated_at DESC LIMIT 64",
        )?;
        stmt.query_map([now_ms() - 45_000], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?))
        })?
        .map(|entry| {
            let (encoded, state) = entry?;
            let mut progress: Progress = serde_json::from_str(&encoded)?;
            progress.transfer_state = state
                .as_deref()
                .map(|state| TransferState::from_database(Some(state), false));
            Ok(progress)
        })
        .collect()
    }

    pub fn clear_history(&self) -> Result<usize> {
        let count = self.connect()?.execute(
            "UPDATE inbox SET hidden = 1 WHERE saved_name IS NOT NULL AND received = 1 AND hidden = 0",
            [],
        )?;
        Ok(count)
    }
}

fn scrub_item(item: &Item) -> Item {
    Item {
        id: item.id.clone(),
        kind: item.kind,
        ts: item.ts,
        text: None,
        name: None,
        relative_path: None,
        size: None,
        sha256: None,
        chat_id: item.chat_id.clone(),
        reply_to: None,
        text_size: None,
        text_sha256: None,
        ttl_sec: None,
    }
}

fn mark_chat_items_deleted(
    transaction: &rusqlite::Transaction<'_>,
    chat_id: &str,
    files: Option<&Files>,
) -> Result<Vec<PathBuf>> {
    let mut paths = Vec::new();
    if let Some(files) = files {
        let mut stmt = transaction.prepare(
            "SELECT id, peer, direction, kind FROM timeline WHERE chat_id = ?1 AND deleted = 0",
        )?;
        let rows: Vec<(String, Peer, String, String)> = stmt
            .query_map([chat_id], |row| {
                Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?))
            })?
            .collect::<rusqlite::Result<_>>()?;
        for (id, peer, direction, kind) in rows {
            if direction == "sent" {
                paths.push(files.outgoing(&id));
                paths.push(files.outgoing_ref(&id));
                paths.push(files.sent_text(&id));
            } else if kind == "text" {
                paths.push(files.received_text(&peer, &id));
            }
        }
    }
    transaction.execute(
        "UPDATE timeline SET deleted = 1 WHERE chat_id = ?1 AND deleted = 0",
        [chat_id],
    )?;
    transaction.execute(
        "UPDATE outbox SET delivered = 1
         WHERE delivered = 0 AND coalesce(json_extract(item, '$.chat_id'), ?2) = ?1",
        params![chat_id, DEFAULT_CHAT],
    )?;
    Ok(paths)
}

const HISTORY_SELECT: &str =
    "SELECT t.seq, t.direction, coalesce(i.item, o.item), coalesce(i.received, o.delivered),
            i.saved_name, t.peer, t.expires_at, control.state
     FROM timeline AS t
     LEFT JOIN inbox AS i ON t.direction = 'received' AND i.id = t.id AND i.peer = t.peer
     LEFT JOIN outbox AS o ON t.direction = 'sent' AND o.id = t.id AND o.peer = t.peer
     LEFT JOIN transfer_controls AS control
          ON control.id = t.id AND control.peer = t.peer AND control.direction = t.direction";

type HistoryRow = (
    i64,
    String,
    String,
    bool,
    Option<String>,
    Peer,
    Option<i64>,
    Option<String>,
);

fn find_transfer(
    connection: &Connection,
    peer: &Peer,
    direction: TransferDirection,
    id: &str,
) -> Result<Option<Transfer>> {
    let (table, completed) = match direction {
        TransferDirection::Sent => ("outbox", "delivered"),
        TransferDirection::Received => ("inbox", "received"),
    };
    let raw: Option<(String, bool, Option<String>)> = connection
        .query_row(
            &format!(
                "SELECT item, {completed}, control.state FROM {table} AS item_record
             LEFT JOIN transfer_controls AS control ON control.id = item_record.id
                  AND control.peer = item_record.peer AND control.direction = ?3
             WHERE item_record.id = ?1 AND item_record.peer = ?2
               AND (item_record.expires_at IS NULL OR item_record.expires_at > ?4)"
            ),
            params![id, peer, direction.as_str(), now_ms()],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let Some((encoded, done, state)) = raw else {
        return Ok(None);
    };
    let item: Item = serde_json::from_str(&encoded)?;
    let state = TransferState::from_database(state.as_deref(), done);
    Ok(item.payload().is_some().then_some(Transfer { item, state }))
}

fn write_transfer_state(
    connection: &Connection,
    peer: &Peer,
    direction: TransferDirection,
    id: &str,
    state: TransferState,
) -> Result<()> {
    connection.execute(
        "INSERT INTO transfer_controls (id, peer, direction, state, updated_at) VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(id, peer, direction) DO UPDATE SET state = excluded.state, updated_at = excluded.updated_at",
        params![id, peer, direction.as_str(), state.as_str(), now_ms()],
    )?;
    if state == TransferState::Canceled {
        connection.execute(
            "DELETE FROM progress WHERE id = ?1 AND direction = ?2",
            params![id, direction.as_str()],
        )?;
        if matches!(direction, TransferDirection::Received) {
            connection.execute(
                "UPDATE inbox SET saved_name = NULL WHERE id = ?1 AND peer = ?2 AND received = 0",
                params![id, peer],
            )?;
        }
    }
    Ok(())
}

fn history_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<HistoryRow> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
        row.get(6)?,
        row.get(7)?,
    ))
}

fn decode_history(row: rusqlite::Result<HistoryRow>) -> Result<HistoryEntry> {
    let (seq, direction, item, delivered, saved_name, peer, expires_at, state) = row?;
    let item: Item = serde_json::from_str(&item)?;
    let transfer_state = item
        .payload()
        .map(|_| TransferState::from_database(state.as_deref(), delivered));
    Ok(HistoryEntry {
        seq,
        item,
        direction,
        delivered,
        saved_name,
        peer,
        expires_at,
        transfer_state,
    })
}

fn chat_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Chat> {
    Ok(Chat {
        id: row.get(0)?,
        title: row.get(1)?,
        created_at: row.get(2)?,
        updated_at: row.get(3)?,
        deleted: row.get::<_, i64>(4)? != 0,
    })
}

fn active_chat_exists(connection: &Connection, id: &str) -> Result<bool> {
    Ok(connection.query_row(
        "SELECT EXISTS (SELECT 1 FROM chats WHERE id = ?1 AND deleted = 0)",
        [id],
        |row| row.get(0),
    )?)
}

fn version(connection: &Connection) -> Result<u32> {
    Ok(connection.pragma_query_value(None, "user_version", |row| row.get(0))?)
}

fn migrate_v1(connection: &Connection) -> Result<()> {
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS identity (
             singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
             cert BLOB NOT NULL, key BLOB NOT NULL, fp TEXT NOT NULL
         );
         CREATE TABLE IF NOT EXISTS trust (
             singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
             peer BLOB, invite BLOB, expires_at INTEGER
         );
         INSERT OR IGNORE INTO trust (singleton) VALUES (1);
         CREATE TABLE IF NOT EXISTS outbox (
             id TEXT NOT NULL, peer BLOB NOT NULL, item TEXT NOT NULL, ts INTEGER NOT NULL,
             delivered INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (id, peer)
         );
         CREATE INDEX IF NOT EXISTS pending_outbox ON outbox (peer, delivered, ts);
         CREATE TABLE IF NOT EXISTS inbox (
             id TEXT NOT NULL, peer BLOB NOT NULL, item TEXT NOT NULL, ts INTEGER NOT NULL,
             saved_name TEXT UNIQUE, received INTEGER NOT NULL DEFAULT 0,
             hidden INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (id, peer)
         );
         CREATE TABLE chats (
             id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
         );
         CREATE TABLE timeline (
             seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, peer BLOB NOT NULL,
             direction TEXT NOT NULL, chat_id TEXT NOT NULL, kind TEXT NOT NULL,
             deleted INTEGER NOT NULL DEFAULT 0, UNIQUE (direction, id, peer)
         );
         CREATE INDEX timeline_chat ON timeline (chat_id, seq) WHERE deleted = 0;
         CREATE INDEX timeline_message ON timeline (id, seq) WHERE deleted = 0;
         CREATE TABLE progress (
             id TEXT NOT NULL, direction TEXT NOT NULL, info TEXT NOT NULL,
             updated_at INTEGER NOT NULL, PRIMARY KEY (id, direction)
         );",
    )?;
    let oldest: Option<i64> = connection.query_row(
        "SELECT min(ts) FROM (SELECT min(ts) AS ts FROM inbox UNION ALL SELECT min(ts) FROM outbox)",
        [],
        |row| row.get(0),
    )?;
    let created = oldest.unwrap_or_else(now_ms);
    connection.execute(
        "INSERT INTO chats VALUES (?1, ?2, ?3, 0)",
        params![DEFAULT_CHAT, date_title(created), created],
    )?;
    connection.execute(
        "INSERT INTO timeline (id, peer, direction, chat_id, kind)
         SELECT id, peer, direction, ?1, json_extract(item, '$.kind') FROM (
            SELECT id, peer, 'received' AS direction, item, ts FROM inbox WHERE received = 1
            UNION ALL
            SELECT id, peer, 'sent', item, ts FROM outbox
         ) ORDER BY ts, id, direction",
        [DEFAULT_CHAT],
    )?;
    connection.pragma_update(None, "user_version", 1)?;
    Ok(())
}

fn migrate_v2(connection: &Connection) -> Result<()> {
    connection.execute_batch(
        "ALTER TABLE chats ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;
         ALTER TABLE trust ADD COLUMN peer_os TEXT;
         ALTER TABLE trust ADD COLUMN peer_name TEXT;
         ALTER TABLE trust ADD COLUMN connected INTEGER NOT NULL DEFAULT 0;
         ALTER TABLE trust ADD COLUMN last_seen INTEGER;
         ALTER TABLE outbox ADD COLUMN expires_at INTEGER;
         ALTER TABLE inbox ADD COLUMN expires_at INTEGER;
         ALTER TABLE timeline ADD COLUMN expires_at INTEGER;
         CREATE INDEX IF NOT EXISTS timeline_expires
             ON timeline (expires_at) WHERE expires_at IS NOT NULL AND deleted = 0;
         DROP TRIGGER IF EXISTS timeline_outbox;
         DROP TRIGGER IF EXISTS timeline_inbox;
         DROP TRIGGER IF EXISTS timeline_received;",
    )?;
    for (trigger, action, direction) in [
        ("timeline_outbox", "AFTER INSERT ON outbox", "sent"),
        (
            "timeline_inbox",
            "AFTER INSERT ON inbox WHEN NEW.received = 1",
            "received",
        ),
        (
            "timeline_received",
            "AFTER UPDATE OF received ON inbox WHEN OLD.received = 0 AND NEW.received = 1",
            "received",
        ),
    ] {
        connection.execute_batch(&format!(
            "CREATE TRIGGER {trigger} {action} BEGIN
               INSERT OR IGNORE INTO timeline (id, peer, direction, chat_id, kind, expires_at)
               VALUES (NEW.id, NEW.peer, '{direction}',
                 coalesce(json_extract(NEW.item, '$.chat_id'), '{DEFAULT_CHAT}'),
                 json_extract(NEW.item, '$.kind'),
                 NEW.expires_at);
             END;"
        ))?;
    }
    connection.pragma_update(None, "user_version", 2)?;
    Ok(())
}

fn migrate_v3(connection: &Connection) -> Result<()> {
    connection.execute_batch(&format!(
        "CREATE TABLE transfer_controls (
             id TEXT NOT NULL, peer BLOB NOT NULL, direction TEXT NOT NULL,
             state TEXT NOT NULL CHECK (state IN ('queued', 'paused', 'canceled', 'failed')),
             updated_at INTEGER NOT NULL,
             PRIMARY KEY (id, peer, direction)
         );
         CREATE TABLE wake_revision (revision INTEGER NOT NULL);
         INSERT INTO wake_revision VALUES (0);
         DROP TRIGGER timeline_inbox;
         CREATE TRIGGER timeline_inbox AFTER INSERT ON inbox BEGIN
            INSERT OR IGNORE INTO timeline (id, peer, direction, chat_id, kind, expires_at)
            VALUES (NEW.id, NEW.peer, 'received',
                    coalesce(json_extract(NEW.item, '$.chat_id'), '{DEFAULT_CHAT}'),
                    json_extract(NEW.item, '$.kind'), NEW.expires_at);
         END;
         INSERT OR IGNORE INTO timeline (id, peer, direction, chat_id, kind, expires_at)
         SELECT id, peer, 'received', coalesce(json_extract(item, '$.chat_id'), '{DEFAULT_CHAT}'),
                json_extract(item, '$.kind'), expires_at
         FROM inbox WHERE received = 0;"
    ))?;
    for (name, event) in [
        ("wake_outbox", "AFTER INSERT ON outbox"),
        ("wake_new_chat", "AFTER INSERT ON chats"),
        (
            "wake_changed_chat",
            "AFTER UPDATE ON chats WHEN NEW.updated_at != OLD.updated_at OR NEW.title != OLD.title",
        ),
        (
            "wake_new_transfer_control",
            "AFTER INSERT ON transfer_controls",
        ),
        (
            "wake_changed_transfer_control",
            "AFTER UPDATE ON transfer_controls WHEN NEW.state != OLD.state",
        ),
    ] {
        connection.execute_batch(&format!(
            "CREATE TRIGGER {name} {event} BEGIN
                UPDATE wake_revision SET revision = revision + 1;
             END;"
        ))?;
    }
    connection.pragma_update(None, "user_version", 3)?;
    Ok(())
}

fn active_peer(connection: &Connection) -> Result<Option<Peer>> {
    Ok(connection.query_row("SELECT peer FROM trust", [], |row| row.get(0))?)
}

#[cfg(unix)]
pub fn private_directory(path: &Path) -> Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(path)
        .with_context(|| format!("create {}", path.display()))
}

#[cfg(not(unix))]
pub fn private_directory(path: &Path) -> Result<()> {
    DirBuilder::new()
        .recursive(true)
        .create(path)
        .with_context(|| format!("create {}", path.display()))
}

#[cfg(unix)]
fn open_private_database_file(path: &Path) -> std::io::Result<std::fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .open(path)
}

#[cfg(not(unix))]
fn open_private_database_file(path: &Path) -> std::io::Result<std::fs::File> {
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .open(path)
}

fn digest(secret: &str) -> Peer {
    Sha256::digest(secret.as_bytes()).into()
}
