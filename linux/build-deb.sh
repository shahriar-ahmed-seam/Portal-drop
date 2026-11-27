#!/bin/sh
set -eu

source_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH='' cd -- "$source_dir/.." && pwd)
binary=${PORTAL_DROP_RELEASE:-"$source_dir/target/release/portal-drop"}
arch=${PORTAL_DROP_DEB_ARCH:-"$(dpkg --print-architecture 2>/dev/null || echo amd64)"}
version=${1:-"$(sed -n 's/^version = "\(.*\)"/\1/p' "$source_dir/Cargo.toml" | head -n 1)"}
out_dir=${PORTAL_DROP_DEB_OUT:-"$repo_dir/dist"}

if [ ! -x "$binary" ]; then
    printf '%s\n' "Build the Rust release binary before packaging (.deb): $binary" >&2
    exit 1
fi
if [ -z "$version" ]; then
    printf '%s\n' "Could not determine package version from linux/Cargo.toml" >&2
    exit 1
fi

stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT

pkg="$stage/portal-drop_${version}_${arch}"
mkdir -p \
    "$pkg/DEBIAN" \
    "$pkg/usr/bin" \
    "$pkg/usr/share/portal-drop/ui" \
    "$pkg/usr/share/portal-drop/sounds" \
    "$pkg/usr/share/applications" \
    "$pkg/usr/share/dbus-1/services" \
    "$pkg/usr/lib/systemd/user/default.target.wants"

install -Dm755 "$binary" "$pkg/usr/bin/portal-drop"
install -Dm755 "$source_dir/desktop/portal-drop-desktop" "$pkg/usr/bin/portal-drop-desktop"
install -Dm644 "$repo_dir/desktop-ui/index.html" "$pkg/usr/share/portal-drop/ui/index.html"

for wav in portal_message.wav portal_file.wav portal_connected.wav; do
    if [ -f "$repo_dir/assets/sounds/$wav" ]; then
        install -Dm644 "$repo_dir/assets/sounds/$wav" "$pkg/usr/share/portal-drop/sounds/$wav"
    fi
done

install -Dm644 "$source_dir/desktop/app.portaldrop.desktop" "$pkg/usr/share/applications/app.portaldrop.desktop"
sed -i 's|^Exec=.*|Exec=/usr/bin/portal-drop-desktop|' "$pkg/usr/share/applications/app.portaldrop.desktop"

cat > "$pkg/usr/share/dbus-1/services/app.portaldrop.service" <<'EOF'
[D-BUS Service]
Name=app.portaldrop
Exec=/usr/bin/portal-drop-desktop --gapplication-service
EOF

install -Dm644 "$source_dir/desktop/portal-drop.svg" "$pkg/usr/share/icons/hicolor/scalable/apps/app.portaldrop.svg"
for sz in 32 48 64 128 256 512; do
    if [ -f "$source_dir/desktop/icons/app.portaldrop-$sz.png" ]; then
        install -Dm644 "$source_dir/desktop/icons/app.portaldrop-$sz.png" \
            "$pkg/usr/share/icons/hicolor/${sz}x${sz}/apps/app.portaldrop.png"
    fi
done

sed 's|^ExecStart=.*|ExecStart=/usr/bin/portal-drop serve|' "$source_dir/systemd/portal-drop.service" \
    > "$pkg/usr/lib/systemd/user/portal-drop.service"
ln -s ../portal-drop.service "$pkg/usr/lib/systemd/user/default.target.wants/portal-drop.service"

installed_kb=$(du -sk "$pkg/usr" | awk '{print $1}')

cat > "$pkg/DEBIAN/control" <<EOF
Package: portal-drop
Version: $version
Section: net
Priority: optional
Architecture: $arch
Installed-Size: $installed_kb
Maintainer: Shahriar Ahmed Seam <shahriarseam17@gmail.com>
Homepage: https://github.com/shahriar-ahmed-seam/Portal-drop
Depends: libc6, python3, python3-gi, gir1.2-gtk-3.0, gir1.2-webkit2-4.1
Recommends: pipewire-bin | pulseaudio-utils | alsa-utils, libnotify-bin, gir1.2-ayatanaappindicator3-0.1, ffmpeg
Description: Direct LAN messaging and file transfer between Linux and Android
 Portal Drop pairs an Android phone with a desktop computer over your local
 network using a QR-pinned TLS certificate. Send text, Markdown, code blocks,
 and resumable SHA-256 verified files without any cloud account or relay.
EOF

cat > "$pkg/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if [ "$1" = "configure" ]; then
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
    fi
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
fi
exit 0
EOF
chmod 755 "$pkg/DEBIAN/postinst"

cat > "$pkg/DEBIAN/postrm" <<'EOF'
#!/bin/sh
set -e
if [ "$1" = "remove" ] || [ "$1" = "purge" ]; then
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
    fi
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
fi
exit 0
EOF
chmod 755 "$pkg/DEBIAN/postrm"

mkdir -p "$out_dir"
deb_path="$out_dir/portal-drop_${version}_${arch}.deb"
dpkg-deb --build --root-owner-group "$pkg" "$deb_path"
printf 'Built %s\n' "$deb_path"
