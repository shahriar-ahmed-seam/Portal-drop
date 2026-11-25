use anyhow::{Context, Result, bail, ensure};
use if_addrs::{Interface, get_if_addrs};
use serde::Serialize;
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket},
    path::Path,
};

use crate::{model::PORT, server::local_listen_address};

const HOTSPOT_HINT_PORT: u16 = 53622;

#[derive(Serialize)]
pub struct LanAddress {
    address: SocketAddrV4,
    interface: String,
    preferred: bool,
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
            Some((
                rank,
                LanAddress {
                    address: SocketAddrV4::new(ip, listen.port()),
                    interface: interface.name,
                    preferred: false,
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

pub fn announce_hotspot(data_dir: &Path, address: &str, fingerprint: &str) -> Result<()> {
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
    let if_addrs::IfAddr::V4(local) = &interface.addr else {
        unreachable!("selected an IPv4 interface")
    };
    let gateway = wifi_gateway(interface).context(
        "no local hotspot gateway found for this address. Enter the desktop address on your phone instead.",
    )?;
    ensure!(
        gateway.is_private()
            && gateway != local.ip
            && (1..=31).contains(&local.prefixlen)
            && (u32::from(gateway) ^ u32::from(local.ip)).leading_zeros()
                >= u32::from(local.prefixlen),
        "the hotspot gateway is outside the selected link"
    );

    let destination = (gateway, HOTSPOT_HINT_PORT);
    // Resolve the route without emitting traffic before sending on the selected source.
    let route = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))?;
    route.connect(destination)?;
    ensure!(
        route.local_addr()?.ip() == IpAddr::V4(*source.ip()),
        "the system routes this gateway through a different interface"
    );
    let socket = UdpSocket::bind((*source.ip(), 0))?;
    socket.set_ttl(1)?;
    socket.send_to(
        format!("PORTALDROP-HOTSPOT/1 {fingerprint} {}\n", listen.port()).as_bytes(),
        destination,
    )?;
    Ok(())
}

#[cfg(target_os = "linux")]
fn wifi_gateway(interface: &Interface) -> Option<Ipv4Addr> {
    let device = Path::new("/sys/class/net").join(&interface.name);
    if !device.join("phy80211").exists() && !device.join("wireless").exists() {
        return None;
    }
    let table = std::fs::read_to_string("/proc/net/route").ok()?;
    table
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
        .map(|(_, gateway)| gateway)
}

#[cfg(windows)]
fn wifi_gateway(interface: &Interface) -> Option<Ipv4Addr> {
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
                        return Some(Ipv4Addr::from(address.sin_addr.S_un.S_addr.to_ne_bytes()));
                    }
                }
            }
        }
        return None;
    }
    None
}

#[cfg(not(any(target_os = "linux", windows)))]
fn wifi_gateway(_interface: &Interface) -> Option<Ipv4Addr> {
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
}
