use std::{
    env, io,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr as TcpAddress},
    os::{
        linux::net::SocketAddrExt,
        unix::net::{SocketAddr, UnixDatagram},
    },
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};
use tokio_rustls::{
    TlsConnector,
    rustls::{
        ClientConfig, RootCertStore,
        crypto::ring,
        pki_types::{CertificateDer, ServerName, pem::PemObject},
    },
};

/// Feed systemd after a successful TLS connection and a response from the HTTP router.
pub fn start(mut address: TcpAddress, certificate: &[u8]) {
    let Some(interval) = env::var("WATCHDOG_USEC")
        .ok()
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|value| *value > 2)
        .map(|value| Duration::from_micros(value / 3))
    else {
        return;
    };
    let connector = match connector(certificate) {
        Ok(connector) => connector,
        Err(error) => {
            eprintln!("Portal Drop watchdog setup: {error}");
            return;
        }
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
        let mut failing = false;
        loop {
            ticks.tick().await;
            let result = tokio::time::timeout(
                Duration::from_secs(2).min(interval),
                probe(address, &connector),
            )
            .await
            .map_err(io::Error::other)
            .and_then(|response| response)
            .and_then(|()| heartbeat());
            if !failing && let Err(error) = &result {
                eprintln!("Portal Drop watchdog: {error}");
            }
            failing = result.is_err();
        }
    });
}

fn connector(certificate: &[u8]) -> io::Result<TlsConnector> {
    let mut roots = RootCertStore::empty();
    roots
        .add(CertificateDer::from_pem_slice(certificate).map_err(io::Error::other)?)
        .map_err(io::Error::other)?;
    let config = ClientConfig::builder_with_provider(Arc::new(ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(io::Error::other)?
        .with_root_certificates(roots)
        .with_no_client_auth();
    Ok(TlsConnector::from(Arc::new(config)))
}

async fn probe(address: TcpAddress, connector: &TlsConnector) -> io::Result<()> {
    let stream = TcpStream::connect(address).await?;
    let name = ServerName::try_from("portaldrop.local").expect("valid Portal Drop hostname");
    let mut connection = connector.connect(name, stream).await?;
    connection
        .write_all(b"GET /v1/info HTTP/1.1\r\nHost: portaldrop.local\r\nConnection: close\r\n\r\n")
        .await?;
    // An unauthenticated request must reach the router and be rejected.
    let mut status = [0; 13];
    connection.read_exact(&mut status).await?;
    if &status == b"HTTP/1.1 401 " {
        Ok(())
    } else {
        Err(io::Error::other("unexpected Portal Drop health response"))
    }
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
