use std::{
    env, io,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr as TcpAddress},
    os::{
        linux::net::SocketAddrExt,
        unix::net::{SocketAddr, UnixDatagram},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};

/// Feed systemd only when the listener accepts a connection and the TLS acceptor replies.
pub fn start(mut address: TcpAddress) {
    let Some(interval) = env::var("WATCHDOG_USEC")
        .ok()
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|value| *value > 2)
        .map(|value| Duration::from_micros(value / 3))
    else {
        return;
    };
    if address.ip().is_unspecified() {
        address.set_ip(match address.ip() {
            IpAddr::V4(_) => Ipv4Addr::LOCALHOST.into(),
            IpAddr::V6(_) => Ipv6Addr::LOCALHOST.into(),
        });
    }
    tokio::spawn(async move {
        let mut ticks = tokio::time::interval(interval);
        ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            ticks.tick().await;
            let keepalive =
                tokio::time::timeout(Duration::from_secs(2).min(interval), probe(address)).await;
            if matches!(keepalive, Ok(Ok(())))
                && let Err(error) = heartbeat()
            {
                eprintln!("Portal Drop watchdog: {error}");
            }
        }
    });
}

async fn probe(address: TcpAddress) -> io::Result<()> {
    let mut connection = TcpStream::connect(address).await?;
    // A fatal TLS alert causes the acceptor to close without a full handshake.
    connection
        .write_all(&[0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x00])
        .await?;
    let _ = connection.read(&mut [0u8; 1]).await?;
    Ok(())
}

fn heartbeat() -> io::Result<()> {
    let address = env::var_os("NOTIFY_SOCKET")
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "notification socket missing"))?;
    let bytes = address.as_encoded_bytes();
    let socket = UnixDatagram::unbound()?;
    socket.set_nonblocking(true)?;
    if let Some(name) = bytes.strip_prefix(b"@") {
        socket.connect_addr(&SocketAddr::from_abstract_name(name)?)?;
    } else {
        socket.connect(address)?;
    }
    socket.send(b"WATCHDOG=1")?;
    Ok(())
}
