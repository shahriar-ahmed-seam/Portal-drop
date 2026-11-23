use std::path::{Path, PathBuf};

#[derive(Clone, Copy, Debug)]
pub enum SoundKind {
    Message,
    File,
    Connected,
}

impl SoundKind {
    fn filename(self) -> &'static str {
        match self {
            Self::Message => "portal_message.wav",
            Self::File => "portal_file.wav",
            Self::Connected => "portal_connected.wav",
        }
    }
}

pub fn find_sound(data_dir: &Path, kind: SoundKind) -> Option<PathBuf> {
    let filename = kind.filename();
    let mut candidates = Vec::new();
    candidates.push(data_dir.join("sounds").join(filename));
    if let Ok(exe) = std::env::current_exe()
        && let Some(dir) = exe.parent()
    {
        candidates.push(dir.join("sounds").join(filename));
        if let Some(parent) = dir.parent() {
            candidates.push(parent.join("share/portal-drop/sounds").join(filename));
        }
    }
    if let Some(xdg_data) = dirs::data_dir() {
        candidates.push(xdg_data.join("portal-drop/sounds").join(filename));
    }
    if let Some(home) = dirs::home_dir() {
        candidates.push(home.join(".local/share/portal-drop/sounds").join(filename));
    }
    candidates.into_iter().find(|path| path.is_file())
}

pub fn play(data_dir: &Path, kind: SoundKind) {
    if std::env::var_os("PORTAL_DROP_NO_SOUND").is_some() {
        return;
    }
    let Some(path) = find_sound(data_dir, kind) else {
        return;
    };
    play_path(path);
}

pub fn play_sync(data_dir: &Path, kind: SoundKind) {
    if std::env::var_os("PORTAL_DROP_NO_SOUND").is_some() {
        return;
    }
    let Some(path) = find_sound(data_dir, kind) else {
        return;
    };
    play_path_sync(path);
}

#[cfg(unix)]
fn play_path(path: PathBuf) {
    tokio::spawn(async move {
        for (program, args) in [
            ("pw-play", vec![path.as_os_str().to_os_string()]),
            ("paplay", vec![path.as_os_str().to_os_string()]),
            ("aplay", vec!["-q".into(), path.as_os_str().to_os_string()]),
        ] {
            if let Ok(status) = tokio::process::Command::new(program)
                .args(&args)
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status()
                .await
                && status.success()
            {
                return;
            }
        }
    });
}

#[cfg(unix)]
fn play_path_sync(path: PathBuf) {
    for (program, args) in [
        ("pw-play", vec![path.as_os_str().to_os_string()]),
        ("paplay", vec![path.as_os_str().to_os_string()]),
        ("aplay", vec!["-q".into(), path.as_os_str().to_os_string()]),
    ] {
        if let Ok(status) = std::process::Command::new(program)
            .args(&args)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            && status.success()
        {
            return;
        }
    }
}

#[cfg(windows)]
fn win_play_sound(path: &Path, async_play: bool) {
    use std::os::windows::ffi::OsStrExt;
    const SND_SYNC: u32 = 0x0000;
    const SND_ASYNC: u32 = 0x0001;
    const SND_NODEFAULT: u32 = 0x0002;
    const SND_FILENAME: u32 = 0x0002_0000;

    #[link(name = "winmm")]
    unsafe extern "system" {
        fn PlaySoundW(psz_sound: *const u16, hmod: *mut core::ffi::c_void, fdw_sound: u32) -> i32;
    }

    let wide: Vec<u16> = path
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let mode = if async_play { SND_ASYNC } else { SND_SYNC };
    unsafe {
        PlaySoundW(
            wide.as_ptr(),
            core::ptr::null_mut(),
            SND_FILENAME | mode | SND_NODEFAULT,
        );
    }
}

#[cfg(windows)]
fn play_path(path: PathBuf) {
    std::thread::spawn(move || win_play_sound(&path, true));
}

#[cfg(windows)]
fn play_path_sync(path: PathBuf) {
    win_play_sound(&path, false);
}
