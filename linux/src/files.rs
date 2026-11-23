use crate::{
    model::{Item, Kind, MAX_FILE_SIZE, MAX_INLINE_TEXT, MAX_NAME, now_ms},
    store::{Incoming, Peer, Store, private_directory},
};
use anyhow::{Context, Result, bail, ensure};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, Metadata, OpenOptions},
    io::{self, BufReader, Read, Write},
    path::{Path, PathBuf},
};
use uuid::Uuid;

pub const BUFFER_SIZE: usize = 128 * 1024;

#[derive(Clone)]
pub struct Files {
    data: PathBuf,
    receive: PathBuf,
}

pub enum Complete {
    Saved(Option<String>),
    InvalidPayload,
    Revoked,
}

impl Files {
    pub fn new(data: &Path, receive: PathBuf) -> Self {
        Self {
            data: data.to_owned(),
            receive,
        }
    }

    pub fn data_dir(&self) -> &Path {
        &self.data
    }

    pub fn receive_dir(&self) -> &Path {
        &self.receive
    }

    pub fn stage_text(&self, mut input: impl Read) -> Result<Item> {
        let mut prefix = Vec::new();
        (&mut input)
            .take((MAX_INLINE_TEXT + 1) as u64)
            .read_to_end(&mut prefix)?;
        let id = Uuid::new_v4().to_string();

        if prefix.len() <= MAX_INLINE_TEXT {
            let text = String::from_utf8(prefix).context("message must be UTF-8")?;
            return Ok(Item::outgoing_text(id, text));
        }

        let target = self.sent_text(&id);
        let result = self
            .stage_payload(&mut prefix.as_slice().chain(input), &target, true)
            .map(|(size, sha256)| Item::outgoing_long_text(id, size, sha256));
        if result.is_err() {
            let _ = fs::remove_file(&target);
        }
        result
    }

    pub fn stage_outgoing(&self, source: &Path) -> Result<Item> {
        ensure!(source.metadata()?.is_file(), "choose an ordinary file");
        let name = safe_name(
            &source
                .file_name()
                .context("missing filename")?
                .to_string_lossy(),
        );
        let id = Uuid::new_v4().to_string();
        let target = self.outgoing(&id);
        let mut input = BufReader::with_capacity(BUFFER_SIZE, File::open(source)?);
        let result = self
            .stage_payload(&mut input, &target, false)
            .map(|(size, hash)| Item::outgoing_file(id, name, size, hash));
        if result.is_err() {
            let _ = fs::remove_file(&target);
        }
        result
    }

    fn stage_payload(
        &self,
        input: &mut impl Read,
        path: &Path,
        text: bool,
    ) -> Result<(u64, String)> {
        let directory = path.parent().context("missing payload folder")?;
        private_directory(directory)?;
        let mut output = open_private_create_new(path)?;
        let (size, hash, valid) = digest_copy(input, &mut output, text)?;
        ensure!(valid, "message must be nonempty UTF-8");
        output.flush()?;
        Ok((size, hash))
    }

    pub fn outgoing(&self, id: &str) -> PathBuf {
        self.data.join("outgoing").join(id)
    }

    pub fn outgoing_payload(&self, item: &Item) -> PathBuf {
        match item.kind {
            Kind::File => self.outgoing(&item.id),
            Kind::Text => self.sent_text(&item.id),
        }
    }

    pub fn sent_text(&self, id: &str) -> PathBuf {
        self.data.join("messages").join(id)
    }

    pub fn received_text(&self, peer: &Peer, id: &str) -> PathBuf {
        self.data.join("messages").join(peer_filename(peer, id))
    }

    pub fn received_file(&self, name: &str) -> PathBuf {
        self.receive.join(name)
    }

    pub fn partial(&self, peer: &Peer, item: &Item) -> PathBuf {
        self.partial_by_kind(peer, item.kind, &item.id)
    }

    pub fn partial_by_kind(&self, peer: &Peer, kind: Kind, id: &str) -> PathBuf {
        match kind {
            Kind::File => self.receive.join(".portal-drop-partial"),
            Kind::Text => self.data.join("messages").join(".partial"),
        }
        .join(peer_filename(peer, id))
    }

    pub fn initial_name(&self, store: &Store, item: &Item) -> Result<String> {
        let base = safe_name(item.name.as_deref().context("missing filename")?);
        self.available_name(store, Path::new(category(&base)), &base, &item.id, None)
    }

    pub async fn finish(
        &self,
        store: &Store,
        peer: &Peer,
        incoming: &Incoming,
    ) -> Result<Complete> {
        let part = self.partial(peer, &incoming.item);
        let check_path = part.clone();
        let text = incoming.item.kind == Kind::Text;
        let (actual_size, actual_hash, valid_text, metadata) =
            tokio::task::spawn_blocking(move || {
                let mut file = File::open(check_path)?;
                let (size, hash, valid) = digest_copy(&mut file, &mut io::sink(), text)?;
                file.sync_all()?;
                Ok::<_, anyhow::Error>((size, hash, valid, file.metadata()?))
            })
            .await??;

        if !valid_text || incoming.item.payload() != Some((actual_size, actual_hash.as_str())) {
            let _ = tokio::fs::remove_file(&part).await;
            return Ok(Complete::InvalidPayload);
        }

        let previous_name = incoming.saved_name.as_deref();
        let final_name = previous_name
            .map(|previous| {
                let path = self.received_file(previous);
                match fs::symlink_metadata(&path) {
                    Ok(existing) if !same_file(&metadata, &existing) => {
                        let previous = Path::new(previous);
                        let folder = previous.parent().context("missing receive folder")?;
                        let base =
                            safe_name(incoming.item.name.as_deref().context("missing filename")?);
                        self.available_name(
                            store,
                            folder,
                            &base,
                            &incoming.item.id,
                            Some(&metadata),
                        )
                    }
                    Ok(_) => Ok(previous.to_owned()),
                    Err(error) if error.kind() == io::ErrorKind::NotFound => {
                        Ok(previous.to_owned())
                    }
                    Err(error) => Err(error.into()),
                }
            })
            .transpose()?;

        let target = match &final_name {
            Some(name) => self.received_file(name),
            None => self.received_text(peer, &incoming.item.id),
        };
        let directory = target.parent().context("missing receive path")?;
        private_directory(directory)?;
        if final_name.is_some() {
            ensure!(
                fs::symlink_metadata(directory)?.file_type().is_dir(),
                "receive category must be an ordinary directory"
            );
        }
        let changed_name = final_name
            .as_deref()
            .filter(|name| Some(*name) != previous_name);
        let refreshed_expiry = incoming
            .item
            .ttl_sec
            .map(|ttl| now_ms().saturating_add((ttl as i64).saturating_mul(1_000)));
        let committed = store.finalize(
            peer,
            &incoming.item.id,
            changed_name,
            refreshed_expiry,
            || {
                match fs::hard_link(&part, &target) {
                    Ok(()) => {}
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                        ensure!(
                            same_file(&metadata, &fs::symlink_metadata(&target)?),
                            "receive filename is already in use"
                        );
                    }
                    Err(error) => {
                        // Cross-device fallback (e.g. Windows FAT/ExFAT or mounted drives)
                        #[cfg(windows)]
                        {
                            let _ = error;
                            fs::copy(&part, &target)?;
                        }
                        #[cfg(unix)]
                        return Err(error.into());
                    }
                }
                sync_directory(directory);
                if let Some(parent) = directory.parent() {
                    sync_directory(parent);
                }
                Ok(())
            },
        )?;
        if !committed {
            return Ok(Complete::Revoked);
        }
        let _ = tokio::fs::remove_file(&part).await;
        Ok(Complete::Saved(final_name))
    }

    fn available_name(
        &self,
        store: &Store,
        folder: &Path,
        base: &str,
        id: &str,
        partial: Option<&Metadata>,
    ) -> Result<String> {
        for index in 0..1000 {
            let file = if index == 0 {
                base.to_owned()
            } else {
                with_suffix(base, id, index)
            };
            let name = folder.join(file).to_string_lossy().replace('\\', "/");
            let occupied = match fs::symlink_metadata(self.received_file(&name)) {
                Ok(existing) => {
                    if partial.is_some_and(|partial| same_file(partial, &existing)) {
                        return Ok(name);
                    }
                    true
                }
                Err(error) if error.kind() == io::ErrorKind::NotFound => false,
                Err(error) => return Err(error.into()),
            };
            if !occupied && !store.reserved_filename(&name)? {
                return Ok(name);
            }
        }
        bail!("receive folder has too many conflicting filenames")
    }
}

pub fn preview_file(path: &Path, limit: usize) -> Result<(String, bool)> {
    let mut bytes = Vec::new();
    let byte_limit = limit * 4 + 1;
    File::open(path)?
        .take(byte_limit as u64)
        .read_to_end(&mut bytes)?;
    let original_length = bytes.len();
    let text = match std::str::from_utf8(&bytes) {
        Ok(text) => text,
        Err(error) if error.error_len().is_none() => {
            std::str::from_utf8(&bytes[..error.valid_up_to()])?
        }
        Err(error) => return Err(error.into()),
    };
    let (preview, truncated) = preview_text(text, limit);
    Ok((
        preview.to_owned(),
        truncated || original_length == byte_limit,
    ))
}

pub fn preview_text(text: &str, limit: usize) -> (&str, bool) {
    match text.char_indices().nth(limit) {
        Some((end, _)) => (&text[..end], true),
        None => (text, false),
    }
}

fn digest_copy(
    input: &mut impl Read,
    output: &mut impl Write,
    text: bool,
) -> Result<(u64, String, bool)> {
    let mut hash = Sha256::new();
    let mut total = 0_u64;
    let mut buffer = vec![0; BUFFER_SIZE + 3];
    let mut tail = 0;
    let mut valid = true;
    let mut contains_content = false;
    loop {
        let length = match input.read(&mut buffer[tail..]) {
            Ok(0) => break,
            Ok(length) => length,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error.into()),
        };
        total += length as u64;
        ensure!(total <= MAX_FILE_SIZE, "payload too large");
        let end = tail + length;
        let chunk = &buffer[tail..end];
        hash.update(chunk);
        output.write_all(chunk)?;

        if text && valid {
            match std::str::from_utf8(&buffer[..end]) {
                Ok(chunk) => {
                    contains_content |= !chunk.trim().is_empty();
                    tail = 0;
                }
                Err(error) => {
                    let prefix = error.valid_up_to();
                    contains_content |= !std::str::from_utf8(&buffer[..prefix])?.trim().is_empty();
                    valid = error.error_len().is_none();
                    tail = if valid { end - prefix } else { 0 };
                    buffer.copy_within(prefix..prefix + tail, 0);
                }
            }
        }
    }
    Ok((
        total,
        hex::encode(hash.finalize()),
        !text || (valid && contains_content && tail == 0),
    ))
}

fn peer_filename(peer: &Peer, id: &str) -> String {
    format!("{}-{id}", hex::encode(peer))
}

fn category(name: &str) -> &'static str {
    let extension = name
        .rsplit_once('.')
        .map(|(_, extension)| extension.to_ascii_lowercase());
    match extension.as_deref() {
        Some(
            "apng" | "avif" | "bmp" | "gif" | "heic" | "heif" | "ico" | "jpeg" | "jpg" | "png"
            | "svg" | "tif" | "tiff" | "webp",
        ) => "Images",
        Some(
            "aac" | "aiff" | "alac" | "flac" | "m4a" | "mid" | "mp3" | "oga" | "ogg" | "opus"
            | "wav" | "wma",
        ) => "Music",
        Some(
            "3gp" | "avi" | "m4v" | "mkv" | "mov" | "mp4" | "mpeg" | "mpg" | "ogv" | "webm" | "wmv",
        ) => "Videos",
        Some(
            "csv" | "doc" | "docx" | "epub" | "html" | "md" | "odf" | "odp" | "ods" | "odt" | "pdf"
            | "ppt" | "pptx" | "rtf" | "tsv" | "txt" | "xls" | "xlsx",
        ) => "Documents",
        Some(
            "7z" | "bz2" | "gz" | "rar" | "tar" | "tbz" | "tgz" | "txz" | "xz" | "zip" | "zst",
        ) => "Compressed",
        Some(
            "apk" | "appimage" | "bat" | "bin" | "deb" | "dmg" | "exe" | "flatpak" | "jar" | "msi"
            | "pkg" | "rpm" | "sh",
        ) => "Programs",
        _ => "Other",
    }
}

fn safe_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|ch| match ch {
            '/' | '\\' => '_',
            #[cfg(windows)]
            ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            ch if ch.is_control() => '_',
            ch => ch,
        })
        .collect();
    let trimmed = cleaned.trim_matches(|ch: char| ch == '.' || ch.is_whitespace());
    if trimmed.is_empty() {
        return "file".to_owned();
    }
    let mut end = MAX_NAME.min(trimmed.len());
    while !trimmed.is_char_boundary(end) {
        end -= 1;
    }
    trimmed[..end].to_owned()
}

fn with_suffix(base: &str, id: &str, index: usize) -> String {
    let suffix = format!(" ({}-{index})", &id[..8]);
    let (stem, extension) = match base.rsplit_once('.') {
        Some((stem, extension)) if extension.len() <= 32 => (stem, &base[stem.len()..]),
        _ => (base, ""),
    };
    let limit = 255 - suffix.len() - extension.len();
    let mut end = limit.min(stem.len());
    while !stem.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}{suffix}{extension}", &stem[..end])
}

#[cfg(unix)]
fn open_private_create_new(path: &Path) -> io::Result<File> {
    use std::os::unix::fs::OpenOptionsExt;
    OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(path)
}

#[cfg(not(unix))]
fn open_private_create_new(path: &Path) -> io::Result<File> {
    OpenOptions::new().create_new(true).write(true).open(path)
}

#[cfg(unix)]
fn sync_directory(directory: &Path) {
    if let Ok(handle) = File::open(directory) {
        let _ = handle.sync_all();
    }
}

#[cfg(not(unix))]
fn sync_directory(_directory: &Path) {}

#[cfg(unix)]
fn same_file(left: &Metadata, right: &Metadata) -> bool {
    use std::os::unix::fs::MetadataExt;
    right.is_file() && left.dev() == right.dev() && left.ino() == right.ino()
}

#[cfg(not(unix))]
fn same_file(left: &Metadata, right: &Metadata) -> bool {
    right.is_file() && left.len() == right.len() && left.modified().ok() == right.modified().ok()
}
