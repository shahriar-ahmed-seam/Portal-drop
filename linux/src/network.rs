use anyhow::{Context, Result, bail, ensure};
use if_addrs::{Interface, get_if_addrs};
use ring::hmac;
use serde::Serialize;
use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket},
    path::Path,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use crate::{model::PORT, server::local_listen_address, store::Peer};

const HOTSPOT_HINT_PORT: u16 = 53622;

#[derive(Serialize)]
pub struct LanAddress {
    address: SocketAddrV4,
    interface: String,
    preferred: bool,
    can_announce: bool,
}

pub fn lan_addresses(data_dir: &Path) -> Result<Vec<LanAddress>> {
    let listen = listen_address(data_dir)?;
    let SocketAddr::V4(listen) = listen else {
        return Ok(Vec::new());
    };
    Ok(select_addresses(
        listen,
        get_if_addrs().context("could not list desktop network adapters")?,
        default_route_source(),
    ))
}

fn select_addresses(
    listen: SocketAddrV4,
    interfaces: Vec<Interface>,
    default_source: Option<Ipv4Addr>,
) -> Vec<LanAddress> {
    let mut endpoints: Vec<_> = interfaces
        .into_iter()
        .filter_map(|interface| {
            let IpAddr::V4(ip) = interface.ip() else {
                return None;
            };
            let bound_here = *listen.ip() == ip;
            if (!listen.ip().is_unspecified() && !bound_here)
                || !usable_ipv4(ip)
                || (interface.is_p2p() && !bound_here)
                || !interface.is_oper_up()
            {
                return None;
            }
            let rank = (
                default_source != Some(ip),
                ip.is_link_local(),
                !ip.is_private(),
            );
            let can_announce = hotspot_target(&interface, None).is_some();
            Some((
                rank,
                LanAddress {
                    address: SocketAddrV4::new(ip, listen.port()),
                    interface: interface.name,
                    preferred: false,
                    can_announce,
                },
            ))
        })
        .collect();
    endpoints.sort_unstable_by(|(rank_a, a), (rank_b, b)| {
        (rank_a, a.address, &a.interface).cmp(&(rank_b, b.address, &b.interface))
    });

    let mut seen = std::collections::HashSet::new();
    endpoints
        .into_iter()
        .map(|(_, endpoint)| endpoint)
        .filter(|endpoint| seen.insert(endpoint.address))
        .enumerate()
        .map(|(index, mut endpoint)| {
            endpoint.preferred = index == 0;
            endpoint
        })
        .collect()
}

fn usable_ipv4(ip: Ipv4Addr) -> bool {
    let first = ip.octets()[0];
    first > 0 && first < 224 && !ip.is_loopback()
}

pub fn announce_hotspot(
    data_dir: &Path,
    address: &str,
    gateway: Option<Ipv4Addr>,
    peer: &Peer,
    fingerprint: &str,
) -> Result<()> {
    let listen = local_listen_address(data_dir).context("start the Portal Drop service first")?;
    let source = address
        .parse::<SocketAddrV4>()
        .or_else(|_| {
            address
                .parse::<Ipv4Addr>()
                .map(|ip| SocketAddrV4::new(ip, listen.port()))
        })
        .context("select the desktop's Wi-Fi IPv4 address")?;
    ensure!(
        listen.is_ipv4()
            && (listen.ip().is_unspecified() || listen.ip() == IpAddr::V4(*source.ip()))
            && source.port() == listen.port(),
        "Portal Drop is not listening on this address"
    );
    ensure!(source.ip().is_private(), "select a local Wi-Fi address");

    let interfaces = get_if_addrs().context("could not list desktop network adapters")?;
    let interface = interfaces
        .iter()
        .find(|interface| {
            interface.ip() == IpAddr::V4(*source.ip())
                && interface.is_oper_up()
                && !interface.is_p2p()
        })
        .context("the selected Wi-Fi address is unavailable")?;
    let target = hotspot_target(interface, gateway)
        .context("choose a local Wi-Fi address, or enter the desktop address on your phone")?;
    if let Some(gateway) = gateway {
        ensure!(
            target.gateway == Some(gateway)
                && route_uses_source(*source.ip(), (gateway, HOTSPOT_HINT_PORT), false),
            "the gateway is not on this Wi-Fi link"
        );
    }
    send_hotspot_hint(target, listen.port(), peer, Some(fingerprint))
}

#[derive(Clone, Copy, Eq, Hash, PartialEq)]
struct HotspotTarget {
    source: Ipv4Addr,
    broadcast: Option<Ipv4Addr>,
    gateway: Option<Ipv4Addr>,
}

fn hotspot_target(interface: &Interface, gateway: Option<Ipv4Addr>) -> Option<HotspotTarget> {
    let if_addrs::IfAddr::V4(local) = &interface.addr else {
        return None;
    };
    if !interface.is_oper_up() || interface.is_p2p() || !local.ip.is_private() {
        return None;
    }
    hotspot_target_for_gateway(local, gateway.or(wifi_gateway(interface)?))
}

fn hotspot_target_for_gateway(
    local: &if_addrs::Ifv4Addr,
    gateway: Option<Ipv4Addr>,
) -> Option<HotspotTarget> {
    if !(1..=31).contains(&local.prefixlen) {
        return None;
    }
    let ip = u32::from(local.ip);
    let mask = u32::from(local.netmask);
    let gateway = gateway.filter(|gateway| {
        gateway.is_private() && *gateway != local.ip && (u32::from(*gateway) & mask) == (ip & mask)
    });
    let broadcast = (8..=30)
        .contains(&local.prefixlen)
        .then(|| Ipv4Addr::from(ip | !mask));
    if broadcast.is_none() && gateway.is_none() {
        return None;
    }
    Some(HotspotTarget {
        source: local.ip,
        broadcast,
        gateway,
    })
}

fn hotspot_hint(peer: &Peer, source: Ipv4Addr, port: u16, seconds: u64) -> String {
    let key = hmac::Key::new(hmac::HMAC_SHA256, peer);
    let signed = format!("PORTALDROP-HOTSPOT/2 {source} {port} {seconds}");
    let signature = hmac::sign(&key, signed.as_bytes());
    format!(
        "PORTALDROP-HOTSPOT/2 {port} {seconds} {}\n",
        hex::encode(signature.as_ref())
    )
}

fn send_hotspot_hint(
    target: HotspotTarget,
    port: u16,
    peer: &Peer,
    legacy_fingerprint: Option<&str>,
) -> Result<()> {
    let socket = UdpSocket::bind((target.source, 0))?;
    socket.set_ttl(1)?;
    let seconds = SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs();
    let hint = hotspot_hint(peer, target.source, port, seconds);
    let mut sent = false;
    if let Some(gateway) = target.gateway {
        let destination = (gateway, HOTSPOT_HINT_PORT);
        if route_uses_source(target.source, destination, false) {
            sent = socket.send_to(hint.as_bytes(), destination).is_ok();
            if let Some(fingerprint) = legacy_fingerprint {
                let legacy = format!("PORTALDROP-HOTSPOT/1 {fingerprint} {port}\n");
                sent |= socket.send_to(legacy.as_bytes(), destination).is_ok();
            }
        }
    }
    if let Some(broadcast) = target.broadcast
        && socket.set_broadcast(true).is_ok()
        && route_uses_source(target.source, (broadcast, HOTSPOT_HINT_PORT), true)
    {
        sent |= socket
            .send_to(hint.as_bytes(), (broadcast, HOTSPOT_HINT_PORT))
            .is_ok();
    }
    ensure!(
        sent,
        "could not announce on this Wi-Fi link. Enter the desktop address on your phone."
    );
    Ok(())
}

fn route_uses_source(source: Ipv4Addr, destination: (Ipv4Addr, u16), broadcast: bool) -> bool {
    // Ask the kernel which link it would pick without emitting traffic.
    UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))
        .and_then(|route| {
            if broadcast {
                route.set_broadcast(true)?;
            }
            route.connect(destination)?;
            route.local_addr()
        })
        .is_ok_and(|address| address.ip() == IpAddr::V4(source))
}

#[derive(Default)]
pub struct HotspotDiscovery {
    // None records an authenticated route, so a later disconnect gets a fresh retry budget.
    attempts: HashMap<HotspotTarget, Option<(u8, Instant)>>,
    peer: Option<Peer>,
}

impl HotspotDiscovery {
    pub fn retry(&mut self, listen: SocketAddrV4, peer: Option<Peer>, connected: &[IpAddr]) {
        if self.peer != peer {
            self.attempts.clear();
            self.peer = peer;
        }
        let Some(peer) = peer else { return };
        let Ok(interfaces) = get_if_addrs() else {
            return;
        };
        let targets: std::collections::HashSet<_> = interfaces
            .iter()
            .filter_map(|interface| hotspot_target(interface, None))
            .filter(|target| listen.ip().is_unspecified() || listen.ip() == &target.source)
            .collect();
        self.attempts.retain(|target, _| targets.contains(target));
        let now = Instant::now();
        for target in targets {
            let progress = self.attempts.entry(target).or_default();
            if connected.contains(&IpAddr::V4(target.source)) {
                *progress = None;
                continue;
            }
            let (count, next) = progress.get_or_insert((0, now));
            if *count < 4 && now >= *next {
                let _ = send_hotspot_hint(target, listen.port(), &peer, None);
                *count += 1;
                *next = now + Duration::from_secs(u64::from(*count) * 2);
            }
        }
    }
}

#[cfg(target_os = "linux")]
fn wifi_gateway(interface: &Interface) -> Option<Option<Ipv4Addr>> {
    let device = Path::new("/sys/class/net").join(&interface.name);
    if !device.join("phy80211").exists() && !device.join("wireless").exists() {
        return None;
    }
    let table = std::fs::read_to_string("/proc/net/route").unwrap_or_default();
    let gateway = table
        .lines()
        .skip(1)
        .filter_map(|line| {
            let mut fields = line.split_ascii_whitespace();
            let name = fields.next()?;
            let destination = fields.next()?;
            let gateway = u32::from_str_radix(fields.next()?, 16).ok()?;
            let flags = u32::from_str_radix(fields.next()?, 16).ok()?;
            let metric = fields.nth(2)?.parse::<u32>().ok()?;
            let mask = fields.next()?;
            if name != interface.name
                || destination != "00000000"
                || mask != "00000000"
                || flags & 3 != 3
            {
                return None;
            }
            Some((metric, Ipv4Addr::from(gateway.to_le_bytes())))
        })
        .min_by_key(|(metric, _)| *metric)
        .map(|(_, gateway)| gateway);
    Some(gateway)
}

#[cfg(windows)]
fn wifi_gateway(interface: &Interface) -> Option<Option<Ipv4Addr>> {
    use std::{mem::MaybeUninit, ptr};
    use windows_sys::Win32::{
        Foundation::{ERROR_BUFFER_OVERFLOW, ERROR_SUCCESS},
        NetworkManagement::IpHelper::{
            GAA_FLAG_INCLUDE_GATEWAYS, GetAdaptersAddresses, IF_TYPE_IEEE80211,
            IP_ADAPTER_ADDRESSES_LH,
        },
        Networking::WinSock::{AF_INET, SOCKADDR_IN},
    };

    let index = interface.index?;
    let mut size = 15_000u32;
    for _ in 0..3 {
        // Allocate naturally aligned storage for the API-owned linked list.
        let entries = (size as usize).div_ceil(size_of::<IP_ADAPTER_ADDRESSES_LH>());
        let mut storage = vec![MaybeUninit::<IP_ADAPTER_ADDRESSES_LH>::uninit(); entries];
        let start = storage.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
        // On success the API initializes the linked list in storage for the remainder of this scope.
        let result = unsafe {
            GetAdaptersAddresses(
                AF_INET.into(),
                GAA_FLAG_INCLUDE_GATEWAYS,
                ptr::null(),
                start,
                &mut size,
            )
        };
        match result {
            ERROR_BUFFER_OVERFLOW => continue,
            ERROR_SUCCESS => {}
            _ => return None,
        }
        // The returned pointers and socket addresses refer to initialized structures in storage.
        unsafe {
            let mut adapter = start;
            while let Some(entry) = adapter.as_ref() {
                adapter = entry.Next;
                if entry.Anonymous1.Anonymous.IfIndex != index || entry.IfType != IF_TYPE_IEEE80211
                {
                    continue;
                }
                let mut gateway = entry.FirstGatewayAddress;
                while let Some(item) = gateway.as_ref() {
                    gateway = item.Next;
                    let address = item.Address;
                    if address.iSockaddrLength as usize >= size_of::<SOCKADDR_IN>()
                        && !address.lpSockaddr.is_null()
                        && (*address.lpSockaddr).sa_family == AF_INET
                    {
                        let address = &*address.lpSockaddr.cast::<SOCKADDR_IN>();
                        return Some(Some(Ipv4Addr::from(
                            address.sin_addr.S_un.S_addr.to_ne_bytes(),
                        )));
                    }
                }
                return Some(None);
            }
        }
        return None;
    }
    None
}

#[cfg(not(any(target_os = "linux", windows)))]
fn wifi_gateway(_interface: &Interface) -> Option<Option<Ipv4Addr>> {
    None
}

fn default_route_source() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    // This asks the kernel for a source address without sending a datagram.
    socket.connect((Ipv4Addr::new(192, 0, 2, 1), 9)).ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) => Some(ip),
        IpAddr::V6(_) => None,
    }
}

fn listen_address(data_dir: &Path) -> Result<SocketAddr> {
    if let Some(address) = local_listen_address(data_dir) {
        return Ok(address);
    }
    let address = match std::env::var("PORTAL_DROP_LISTEN") {
        Ok(value) => value
            .parse::<SocketAddr>()
            .context("invalid PORTAL_DROP_LISTEN")?,
        Err(std::env::VarError::NotPresent) => SocketAddr::from((Ipv4Addr::UNSPECIFIED, PORT)),
        Err(error) => return Err(error).context("invalid PORTAL_DROP_LISTEN"),
    };
    ensure!(
        address.port() > 0,
        "start the service to determine its selected port"
    );
    Ok(address)
}

pub fn pairing_address(data_dir: &Path, hint: Option<&str>) -> Result<String> {
    let Some(hint) = hint else {
        return lan_addresses(data_dir)?
            .first()
            .map(|endpoint| endpoint.address.to_string())
            .context("join or start a local Wi-Fi network, or set --address to the desktop's LAN address");
    };
    let (host, port) = match hint.split_once(':') {
        Some((host, port)) => (host, port.parse::<u16>().context("invalid --address port")?),
        None => (hint, listen_address(data_dir)?.port()),
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

#[cfg(test)]
mod tests {
    use super::*;
    use if_addrs::{IfAddr, IfOperStatus, Ifv4Addr};

    fn adapter(name: &str, ip: Ipv4Addr, status: IfOperStatus) -> Interface {
        Interface {
            name: name.into(),
            addr: IfAddr::V4(Ifv4Addr {
                ip,
                netmask: Ipv4Addr::new(255, 255, 255, 0),
                prefixlen: 24,
                broadcast: None,
            }),
            index: None,
            oper_status: status,
            is_p2p: false,
            #[cfg(windows)]
            adapter_name: name.into(),
        }
    }

    #[test]
    fn lists_hotspot_address_without_a_default_route() {
        let hotspot = Ipv4Addr::new(192, 168, 100, 23);
        let endpoints = select_addresses(
            SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 53621),
            vec![
                adapter("loopback", Ipv4Addr::LOCALHOST, IfOperStatus::Up),
                adapter("offline", Ipv4Addr::new(10, 1, 2, 3), IfOperStatus::Down),
                adapter("hotspot", hotspot, IfOperStatus::Up),
            ],
            None,
        );
        assert_eq!(endpoints.len(), 1);
        assert_eq!(endpoints[0].address, SocketAddrV4::new(hotspot, 53621));
        assert!(endpoints[0].preferred);
    }

    #[test]
    fn respects_default_route_and_explicit_listener() {
        let wifi = Ipv4Addr::new(192, 168, 100, 23);
        let wired = Ipv4Addr::new(10, 1, 2, 3);
        let interfaces = vec![
            adapter("wired", wired, IfOperStatus::Up),
            adapter("wifi", wifi, IfOperStatus::Up),
        ];
        let endpoints = select_addresses(
            SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 53621),
            interfaces.clone(),
            Some(wifi),
        );
        assert_eq!(endpoints[0].address.ip(), &wifi);
        let endpoints = select_addresses(SocketAddrV4::new(wired, 42621), interfaces, Some(wifi));
        assert_eq!(endpoints.len(), 1);
        assert_eq!(endpoints[0].address, SocketAddrV4::new(wired, 42621));
    }

    #[test]
    fn hotspot_hint_is_available_without_a_gateway() {
        let local = Ifv4Addr {
            ip: Ipv4Addr::new(192, 168, 44, 7),
            netmask: Ipv4Addr::new(255, 255, 255, 0),
            prefixlen: 24,
            broadcast: None,
        };
        let target =
            hotspot_target_for_gateway(&local, Some(Ipv4Addr::new(192, 168, 7, 1))).unwrap();
        assert_eq!(target.broadcast, Some(Ipv4Addr::new(192, 168, 44, 255)));
        assert_eq!(target.gateway, None);
        let gateway = Ipv4Addr::new(192, 168, 44, 1);
        assert_eq!(
            hotspot_target_for_gateway(&local, Some(gateway))
                .unwrap()
                .gateway,
            Some(gateway)
        );
    }

    #[test]
    fn hotspot_hint_matches_android_wire_format() {
        use sha2::{Digest, Sha256};
        let peer = Sha256::digest("21".repeat(32).as_bytes()).into();
        assert_eq!(
            hotspot_hint(&peer, Ipv4Addr::new(192, 168, 44, 7), 53621, 1_750_000_000),
            "PORTALDROP-HOTSPOT/2 53621 1750000000 2e2998ac86512087f90974a0fdb158ff9bbe6d653a84dec03c23f7b824eb47e0\n"
        );
    }
}
