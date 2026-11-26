use serde_json::{Value, json};
use std::{
    collections::HashSet,
    net::Ipv4Addr,
    ptr::{NonNull, null, null_mut},
    sync::Mutex,
    thread,
    time::{Duration, Instant},
};
use windows_sys::{
    Win32::{
        Foundation::{
            ERROR_ACCESS_DENIED, ERROR_INVALID_STATE, ERROR_NOT_FOUND, ERROR_SERVICE_NOT_ACTIVE,
            ERROR_SUCCESS, HANDLE,
        },
        NetworkManagement::{
            IpHelper::{
                ConvertInterfaceGuidToLuid, ConvertInterfaceLuidToIndex, FreeMibTable,
                GetUnicastIpAddressTable, MIB_UNICASTIPADDRESS_ROW, MIB_UNICASTIPADDRESS_TABLE,
            },
            Ndis::NET_LUID_LH,
            WiFi::*,
        },
        Networking::WinSock::{AF_INET, IpDadStatePreferred},
    },
    core::GUID,
};

static JOIN: Mutex<()> = Mutex::new(());

fn check(code: u32, reason: &str) -> Result<(), String> {
    match code {
        ERROR_SUCCESS => Ok(()),
        ERROR_ACCESS_DENIED => Err(
            "Windows blocked Wi-Fi access. Allow desktop app location access or join in Windows Wi-Fi settings."
                .into(),
        ),
        ERROR_SERVICE_NOT_ACTIVE => Err("Turn on Wi-Fi in Windows settings.".into()),
        _ => Err(format!("{reason} Windows error {code}.")),
    }
}

struct Handle(HANDLE);

impl Handle {
    fn open() -> Result<Self, String> {
        let mut version = 0;
        let mut handle = null_mut();
        check(
            unsafe { WlanOpenHandle(2, null(), &mut version, &mut handle) },
            "Windows Wi-Fi is unavailable.",
        )?;
        Ok(Self(handle))
    }

    fn interfaces(&self) -> Result<Vec<GUID>, String> {
        let mut raw = null_mut::<WLAN_INTERFACE_INFO_LIST>();
        check(
            unsafe { WlanEnumInterfaces(self.0, null(), &mut raw) },
            "Could not list Wi-Fi adapters.",
        )?;
        let list = Memory::new(raw)?;
        // WLAN allocates dwNumberOfItems trailing WLAN_INTERFACE_INFO records.
        unsafe {
            let count = (*list.ptr()).dwNumberOfItems as usize;
            if count == 0 {
                return Err("Windows could not find an available Wi-Fi adapter.".into());
            }
            let entries = std::ptr::addr_of!((*list.ptr()).InterfaceInfo).cast();
            Ok(
                std::slice::from_raw_parts::<WLAN_INTERFACE_INFO>(entries, count)
                    .iter()
                    .map(|item| item.InterfaceGuid)
                    .collect(),
            )
        }
    }

    fn nearby(&self, adapters: &[GUID]) -> Result<Vec<Network>, String> {
        let mut networks = Vec::new();
        let mut failures = Vec::new();
        for adapter in adapters {
            match self.nearby_adapter(*adapter) {
                Ok(items) => networks.extend(items),
                Err(error) => failures.push(error),
            }
        }
        if failures.len() == adapters.len() {
            return Err(failures.remove(0));
        }
        networks.sort_by(|a, b| {
            let a_portal = a.name().starts_with("Portal Drop");
            let b_portal = b.name().starts_with("Portal Drop");
            (b_portal, b.signal).cmp(&(a_portal, a.signal))
        });
        Ok(networks)
    }

    fn nearby_adapter(&self, adapter: GUID) -> Result<Vec<Network>, String> {
        let mut raw = null_mut::<WLAN_AVAILABLE_NETWORK_LIST>();
        check(
            unsafe { WlanGetAvailableNetworkList(self.0, &adapter, 0, null(), &mut raw) },
            "Could not list nearby Wi-Fi networks.",
        )?;
        let list = Memory::new(raw)?;
        // WLAN allocates dwNumberOfItems trailing WLAN_AVAILABLE_NETWORK records.
        unsafe {
            let count = (*list.ptr()).dwNumberOfItems as usize;
            if count == 0 {
                return Ok(Vec::new());
            }
            let entries = std::ptr::addr_of!((*list.ptr()).Network).cast();
            Ok(
                std::slice::from_raw_parts::<WLAN_AVAILABLE_NETWORK>(entries, count)
                    .iter()
                    .filter_map(|entry| Network::from_wlan(adapter, entry))
                    .collect(),
            )
        }
    }

    fn is_connected(&self, network: &Network) -> Result<bool, String> {
        let mut raw = null_mut();
        let mut size = 0;
        let code = unsafe {
            WlanQueryInterface(
                self.0,
                &network.adapter,
                wlan_intf_opcode_current_connection,
                null(),
                &mut size,
                &mut raw,
                null_mut(),
            )
        };
        if matches!(code, ERROR_INVALID_STATE | ERROR_NOT_FOUND) {
            return Ok(false);
        }
        check(code, "Could not verify the Windows Wi-Fi connection.")?;
        let data = Memory::new(raw.cast::<WLAN_CONNECTION_ATTRIBUTES>())?;
        if size as usize != std::mem::size_of::<WLAN_CONNECTION_ATTRIBUTES>() {
            return Err("Windows returned an unexpected Wi-Fi connection record.".into());
        }
        // The successful query returned a WLAN_CONNECTION_ATTRIBUTES of the expected size.
        let connection = unsafe { &*data.ptr() };
        let security = &connection.wlanSecurityAttributes;
        Ok(connection.isState == wlan_interface_state_connected
            && ssid(&connection.wlanAssociationAttributes.dot11Ssid) == Some(&network.ssid)
            && security.dot11AuthAlgorithm == network.security.authentication())
    }
}

impl Drop for Handle {
    fn drop(&mut self) {
        unsafe { WlanCloseHandle(self.0, null()) };
    }
}

struct Memory<T>(NonNull<T>);

impl<T> Memory<T> {
    fn new(raw: *mut T) -> Result<Self, String> {
        NonNull::new(raw)
            .map(Self)
            .ok_or_else(|| "Windows returned no Wi-Fi information.".into())
    }

    fn ptr(&self) -> *mut T {
        self.0.as_ptr()
    }
}

impl<T> Drop for Memory<T> {
    fn drop(&mut self) {
        unsafe { WlanFreeMemory(self.ptr().cast()) };
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Security {
    Open,
    Wpa2,
}

impl Security {
    fn authentication(self) -> DOT11_AUTH_ALGORITHM {
        match self {
            Self::Open => DOT11_AUTH_ALGO_80211_OPEN,
            Self::Wpa2 => DOT11_AUTH_ALGO_RSNA_PSK,
        }
    }
}

struct Network {
    adapter: GUID,
    ssid: Vec<u8>,
    security: Security,
    signal: u32,
}

impl Network {
    fn from_wlan(adapter: GUID, entry: &WLAN_AVAILABLE_NETWORK) -> Option<Self> {
        if entry.bNetworkConnectable == 0 || entry.dot11BssType != dot11_BSS_type_infrastructure {
            return None;
        }
        let security = match (
            entry.bSecurityEnabled != 0,
            entry.dot11DefaultAuthAlgorithm,
            entry.dot11DefaultCipherAlgorithm,
        ) {
            (false, DOT11_AUTH_ALGO_80211_OPEN, DOT11_CIPHER_ALGO_NONE) => Security::Open,
            (true, DOT11_AUTH_ALGO_RSNA_PSK, DOT11_CIPHER_ALGO_CCMP) => Security::Wpa2,
            _ => return None,
        };
        Some(Self {
            adapter,
            ssid: ssid(&entry.dot11Ssid)?.to_vec(),
            security,
            signal: entry.wlanSignalQuality,
        })
    }

    fn id(&self) -> String {
        format!(
            "{:08x}{:04x}{:04x}{}-{}-{}",
            self.adapter.data1,
            self.adapter.data2,
            self.adapter.data3,
            hex(&self.adapter.data4),
            hex(&self.ssid),
            self.security.authentication(),
        )
    }

    fn name(&self) -> String {
        String::from_utf8_lossy(&self.ssid).into_owned()
    }

    fn profile(&self, password: Option<&str>) -> Result<Vec<u16>, String> {
        let (auth, cipher, key) = match self.security {
            Security::Open => ("open", "none", String::new()),
            Security::Wpa2 => {
                let Some(password) = password.filter(|value| {
                    (8..=63).contains(&value.len())
                        && value.bytes().all(|byte| (b' '..=b'~').contains(&byte))
                }) else {
                    return Err("Enter a Wi-Fi password with 8–63 printable characters.".into());
                };
                let escaped = password
                    .replace('&', "&amp;")
                    .replace('<', "&lt;")
                    .replace('>', "&gt;");
                (
                    "WPA2PSK",
                    "AES",
                    format!(
                        "<sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{escaped}</keyMaterial></sharedKey>"
                    ),
                )
            }
        };
        let ssid = hex(&self.ssid);
        // Encode the raw SSID in hex so every login uses precisely the selected network.
        let xml = format!(
            "<WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\">\
             <name>Portal Drop {ssid}</name><SSIDConfig><SSID><hex>{ssid}</hex></SSID></SSIDConfig>\
             <connectionType>ESS</connectionType><connectionMode>manual</connectionMode>\
             <MSM><security><authEncryption><authentication>{auth}</authentication>\
             <encryption>{cipher}</encryption><useOneX>false</useOneX></authEncryption>{key}</security></MSM></WLANProfile>"
        );
        Ok(xml.encode_utf16().chain(std::iter::once(0)).collect())
    }
}

fn ssid(value: &DOT11_SSID) -> Option<&[u8]> {
    value
        .ucSSID
        .get(..value.uSSIDLength as usize)
        .filter(|bytes| !bytes.is_empty())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02X}")).collect()
}

fn interface_index(adapter: &GUID) -> Option<u32> {
    let mut luid = NET_LUID_LH::default();
    let mut index = 0;
    (unsafe { ConvertInterfaceGuidToLuid(adapter, &mut luid) } == ERROR_SUCCESS
        && unsafe { ConvertInterfaceLuidToIndex(&luid, &mut index) } == ERROR_SUCCESS)
        .then_some(index)
}

fn local_address(index: u32) -> Option<Ipv4Addr> {
    let mut raw = null_mut::<MIB_UNICASTIPADDRESS_TABLE>();
    if unsafe { GetUnicastIpAddressTable(AF_INET, &mut raw) } != ERROR_SUCCESS || raw.is_null() {
        return None;
    }
    // The API allocates NumEntries trailing rows, all valid until FreeMibTable.
    unsafe {
        let entries = std::ptr::addr_of!((*raw).Table).cast();
        let address = std::slice::from_raw_parts::<MIB_UNICASTIPADDRESS_ROW>(
            entries,
            (*raw).NumEntries as usize,
        )
        .iter()
        .filter(|row| {
            row.InterfaceIndex == index
                && row.DadState == IpDadStatePreferred
                && !row.SkipAsSource
                && row.Address.si_family == AF_INET
        })
        .map(|row| Ipv4Addr::from(row.Address.Ipv4.sin_addr.S_un.S_addr.to_ne_bytes()))
        .find(|address| {
            !address.is_unspecified() && !address.is_loopback() && !address.is_link_local()
        });
        FreeMibTable(raw.cast());
        address
    }
}

pub fn networks(scan: bool) -> Result<Value, String> {
    let handle = Handle::open()?;
    let adapters = handle.interfaces()?;
    if scan {
        let mut requested = false;
        for adapter in &adapters {
            let code = unsafe { WlanScan(handle.0, adapter, null(), null(), null()) };
            requested |= code == ERROR_SUCCESS;
        }
        if requested {
            // WlanScan returns immediately and the driver is allowed four seconds to finish.
            thread::sleep(Duration::from_secs(4));
        }
    }
    let mut seen = HashSet::new();
    let items: Vec<_> = handle
        .nearby(&adapters)?
        .into_iter()
        .filter(|item| seen.insert((item.ssid.clone(), item.security)))
        .map(|item| {
            json!({
                "id": item.id(),
                "ssid": item.name(),
                "requiresPassword": item.security == Security::Wpa2,
            })
        })
        .collect();
    Ok(json!(items))
}

pub fn join(id: &str, password: Option<&str>) -> Result<Value, String> {
    let _join = JOIN
        .try_lock()
        .map_err(|_| "Windows is already joining Wi-Fi.")?;
    let handle = Handle::open()?;
    let adapters = handle.interfaces()?;
    let network = handle
        .nearby(&adapters)?
        .into_iter()
        .find(|item| item.id() == id)
        .ok_or("This network is no longer visible. Scan again.")?;
    let index = interface_index(&network.adapter);
    if handle.is_connected(&network)? {
        return Ok(json!({ "ssid": network.name(), "address": index.and_then(local_address) }));
    }
    let mut profile = network.profile(password)?;
    let request = WLAN_CONNECTION_PARAMETERS {
        wlanConnectionMode: wlan_connection_mode_temporary_profile,
        strProfile: profile.as_ptr(),
        dot11BssType: dot11_BSS_type_infrastructure,
        ..Default::default()
    };
    let code = unsafe { WlanConnect(handle.0, &network.adapter, &request, null()) };
    profile.fill(0);
    check(code, "Windows could not start the Wi-Fi connection.")?;
    let deadline = Instant::now() + Duration::from_secs(22);
    let mut addressed_by = deadline;
    loop {
        let now = Instant::now();
        let connected = handle.is_connected(&network)?;
        if connected {
            addressed_by = addressed_by.min(now + Duration::from_secs(5));
            let address = index.and_then(local_address);
            if address.is_some() || index.is_none() || now >= addressed_by {
                return Ok(json!({ "ssid": network.name(), "address": address }));
            }
        }
        if now >= deadline {
            break;
        }
        thread::sleep(Duration::from_millis(350));
    }
    Err("The Wi-Fi connection timed out. Check the hotspot or join from Windows settings.".into())
}
