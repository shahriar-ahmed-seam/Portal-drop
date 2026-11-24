use super::{EventLoopProxy, ICON_RGBA, PathBuf, ServiceHandle, UserEvent, Value, run_cli};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::Duration,
};
use tray_icon::{
    Icon, MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent,
    menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem},
};

#[derive(Debug)]
pub enum Action {
    Open,
    Clipboard,
    ToggleService,
    Quit,
}

#[derive(Debug)]
pub struct Status {
    active: bool,
    paired: bool,
    connected: bool,
    unread: u64,
}

pub struct Tray {
    icon: TrayIcon,
    summary: MenuItem,
    clipboard: MenuItem,
    service: MenuItem,
}

impl Tray {
    pub fn new(proxy: &EventLoopProxy<UserEvent>) -> Result<Self, Box<dyn std::error::Error>> {
        let menu = Menu::new();
        let summary = MenuItem::new("Waiting", false, None);
        let open = MenuItem::new("Open Portal Drop", true, None);
        let clipboard = MenuItem::new("Send clipboard", false, None);
        let service = MenuItem::new("Pause service", true, None);
        let quit = MenuItem::new("Quit Portal Drop", true, None);
        menu.append_items(&[
            &summary,
            &open,
            &clipboard,
            &service,
            &PredefinedMenuItem::separator(),
            &quit,
        ])?;
        let icon = TrayIconBuilder::new()
            .with_tooltip("Portal Drop")
            .with_icon(Icon::from_rgba(ICON_RGBA.to_vec(), 64, 64)?)
            .with_menu(Box::new(menu))
            .with_menu_on_left_click(false)
            .build()?;
        let menu_proxy = proxy.clone();
        let ids = [
            open.id().clone(),
            clipboard.id().clone(),
            service.id().clone(),
            quit.id().clone(),
        ];
        MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
            let Some(index) = ids.iter().position(|id| *id == event.id) else {
                return;
            };
            let action = [
                Action::Open,
                Action::Clipboard,
                Action::ToggleService,
                Action::Quit,
            ];
            if let Some(action) = action.into_iter().nth(index) {
                let _ = menu_proxy.send_event(UserEvent::Tray(action));
            }
        }));
        let click_proxy = proxy.clone();
        TrayIconEvent::set_event_handler(Some(move |event| {
            if matches!(
                event,
                TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                }
            ) {
                let _ = click_proxy.send_event(UserEvent::Tray(Action::Open));
            }
        }));
        Ok(Self {
            icon,
            summary,
            clipboard,
            service,
        })
    }

    pub fn render(&self, status: Status) {
        let connection = if !status.active {
            "Paused"
        } else if status.connected {
            "Connected"
        } else {
            "Waiting"
        };
        let label = if status.unread > 0 {
            format!("{connection} · {} unread", status.unread)
        } else {
            connection.into()
        };
        self.summary.set_text(&label);
        let _ = self
            .icon
            .set_tooltip(Some(format!("Portal Drop · {label}")));
        self.clipboard.set_enabled(status.paired);
        self.service.set_text(if status.active {
            "Pause service"
        } else {
            "Resume service"
        });
    }
}

pub fn watch(
    cli: PathBuf,
    dir: PathBuf,
    service: Arc<ServiceHandle>,
    proxy: EventLoopProxy<UserEvent>,
) -> Arc<AtomicBool> {
    let focused = Arc::new(AtomicBool::new(true));
    let window_focused = focused.clone();
    thread::spawn(move || {
        let mut cursor = None;
        let mut unread = 0u64;
        loop {
            let active = service.is_running(&dir);
            if let Ok(raw) = run_cli(&cli, &["status".into(), "--json".into()], None)
                && let Ok(status) = serde_json::from_str::<Value>(&raw)
            {
                if let Some(after) = cursor {
                    let args = [
                        "events".into(),
                        "--json".into(),
                        "--after".into(),
                        format!("{after}"),
                    ];
                    if let Ok(raw) = run_cli(&cli, &args, None)
                        && let Ok(rows) = serde_json::from_str::<Vec<Value>>(&raw)
                    {
                        unread = unread.saturating_add(
                            rows.iter()
                                .filter(|item| item["direction"] == "received")
                                .count() as u64,
                        );
                        cursor = rows.last().and_then(|row| row["seq"].as_i64()).or(cursor);
                    }
                } else {
                    let args = [
                        "history".into(),
                        "--json".into(),
                        "--limit".into(),
                        "1".into(),
                    ];
                    if let Ok(raw) = run_cli(&cli, &args, None)
                        && let Ok(history) = serde_json::from_str::<Value>(&raw)
                    {
                        cursor = Some(history["items"][0]["seq"].as_i64().unwrap_or(0));
                    }
                }
                if window_focused.load(Ordering::Relaxed) {
                    unread = 0;
                }
                if proxy
                    .send_event(UserEvent::TrayStatus(Status {
                        active,
                        paired: status["paired"] == true,
                        connected: status["connected"] == true,
                        unread,
                    }))
                    .is_err()
                {
                    return;
                }
            }
            thread::sleep(Duration::from_secs(5));
        }
    });
    focused
}

pub fn send_clipboard(cli: PathBuf) {
    thread::spawn(move || {
        let result = (|| {
            let text: String = clipboard_win::get_clipboard(clipboard_win::formats::Unicode)
                .map_err(|_| "The clipboard has no text to send.")?;
            if text.trim().is_empty() {
                return Err("The clipboard has no text to send.");
            }
            let raw = run_cli(
                &cli,
                &["snapshot".into(), "--limit".into(), "1".into()],
                None,
            )
            .map_err(|_| "Could not find a chat on the paired device.")?;
            let snapshot = serde_json::from_str::<Value>(&raw)
                .map_err(|_| "Could not read the active chat.")?;
            let chat = snapshot["chat_id"]
                .as_str()
                .ok_or("Could not find an active chat.")?;
            run_cli(
                &cli,
                &[
                    "send-text".into(),
                    "--chat".into(),
                    chat.into(),
                    "--ttl".into(),
                    "300".into(),
                    "-".into(),
                ],
                Some(&text),
            )
            .map_err(|_| "Could not send the clipboard. Make sure an Android device is paired.")?;
            Ok(())
        })();
        if let Err(error) = result {
            rfd::MessageDialog::new()
                .set_title("Portal Drop")
                .set_description(error)
                .show();
        }
    });
}
