#!/bin/sh
set -eu
source_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH='' cd -- "$source_dir/.." && pwd)
data_dir=${XDG_DATA_HOME:-"$HOME/.local/share"}
config_dir=${XDG_CONFIG_HOME:-"$HOME/.config"}
bin_dir="$HOME/.local/bin"
binary=${PORTAL_DROP_RELEASE:-"$source_dir/target/release/portal-drop"}

if [ ! -x "$binary" ]; then
    printf '%s\n' 'Build the Rust release binary before installing Portal Drop.' >&2
    exit 1
fi
/usr/bin/python3 -c 'import gi; gi.require_version("Gtk", "3.0"); gi.require_version("WebKit2", "4.1"); from gi.repository import Gtk, WebKit2'
install -Dm755 "$binary" "$bin_dir/portal-drop"
install -Dm755 "$source_dir/desktop/portal-drop-desktop" "$bin_dir/portal-drop-desktop"
install -Dm644 "$repo_dir/desktop-ui/index.html" "$data_dir/portal-drop/ui/index.html"
install -Dm644 "$source_dir/desktop/app.portaldrop.desktop" "$data_dir/applications/app.portaldrop.desktop"
sed -i "s|^Exec=.*|Exec=\"$bin_dir/portal-drop-desktop\"|" "$data_dir/applications/app.portaldrop.desktop"
mkdir -p "$data_dir/portal-drop/sounds"
for wav in portal_message.wav portal_file.wav portal_connected.wav; do
    if [ -f "$repo_dir/assets/sounds/$wav" ]; then
        install -Dm644 "$repo_dir/assets/sounds/$wav" "$data_dir/portal-drop/sounds/$wav"
    fi
done
mkdir -p "$data_dir/dbus-1/services"
cat > "$data_dir/dbus-1/services/app.portaldrop.service" <<END
[D-BUS Service]
Name=app.portaldrop
Exec="$bin_dir/portal-drop-desktop" --gapplication-service
END
install -Dm644 "$source_dir/desktop/portal-drop.svg" "$data_dir/icons/hicolor/scalable/apps/app.portaldrop.svg"
for sz in 32 48 64 128 256 512; do
    if [ -f "$source_dir/desktop/icons/app.portaldrop-$sz.png" ]; then
        install -Dm644 "$source_dir/desktop/icons/app.portaldrop-$sz.png" "$data_dir/icons/hicolor/${sz}x${sz}/apps/app.portaldrop.png"
    fi
done
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache --force --ignore-theme-index "$data_dir/icons/hicolor"
fi
install -Dm644 "$source_dir/systemd/portal-drop.service" "$config_dir/systemd/user/portal-drop.service"
if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$data_dir/applications/app.portaldrop.desktop"
fi
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$data_dir/applications"
fi
systemctl --user daemon-reload
if systemctl --user is-active --quiet portal-drop.service; then
    systemctl --user restart portal-drop.service
fi
systemctl --user enable --now portal-drop.service
