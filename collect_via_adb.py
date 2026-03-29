"""
ADB Data Collector — Collects phone data via ADB and sends to backend.
Extracts: bKash/Nagad, Telecom recharges, Uber/Pathao, Device Info,
          Installed Apps, Call Behavior analysis, and sends to Netlify API.

Usage:
    python collect_via_adb.py
    python collect_via_adb.py --device DEVICE_SERIAL
"""

import subprocess
import json
import re
import sys
import time
import hashlib
import urllib.request
import urllib.error
from datetime import datetime, timedelta
from collections import defaultdict

# ─── Configuration ──────────────────────────────────────────────────────────

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
SERVER_URL = "https://datacollector-panel.netlify.app/api/collect"

# ─── ADB Helpers ────────────────────────────────────────────────────────────

def adb_cmd(cmd, device=None):
    """Run an ADB shell command and return output."""
    full = [ADB]
    if device:
        full += ["-s", device]
    full += ["shell"] + cmd.split()
    try:
        result = subprocess.run(full, capture_output=True, text=True, timeout=30,
                                encoding='utf-8', errors='replace')
        return result.stdout.strip()
    except Exception as e:
        print(f"  [ADB ERROR] {e}")
        return ""

def adb_cmd_raw(args, device=None):
    """Run an ADB command (not shell)."""
    full = [ADB]
    if device:
        full += ["-s", device]
    full += args
    try:
        result = subprocess.run(full, capture_output=True, text=True, timeout=30,
                                encoding='utf-8', errors='replace')
        return result.stdout.strip()
    except Exception as e:
        print(f"  [ADB ERROR] {e}")
        return ""

def get_device_id(device=None):
    """Get Android ID (same as Settings.Secure.ANDROID_ID)."""
    return adb_cmd("settings get secure android_id", device)

def send_to_server(data_type, data, device_id):
    """POST data to the backend API."""
    if not data:
        print(f"  [{data_type}] No data to send.")
        return
    payload = json.dumps({
        "type": data_type,
        "data": data,
        "device_id": device_id
    }).encode('utf-8')

    req = urllib.request.Request(
        SERVER_URL,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            result = json.loads(resp.read().decode())
            print(f"  [{data_type}] Sent {len(data)} records -> {result}")
    except urllib.error.HTTPError as e:
        body = e.read().decode() if e.fp else ""
        print(f"  [{data_type}] HTTP {e.code}: {body}")
    except Exception as e:
        print(f"  [{data_type}] Send error: {e}")


# ═══════════════════════════════════════════════════════════════════════════
# 1. SMS-BASED: bKash, Nagad, Telecom, Uber, Pathao
# ═══════════════════════════════════════════════════════════════════════════

BKASH_KEYWORDS = ["bkash", "16247"]
NAGAD_KEYWORDS = ["nagad", "16167"]
UBER_KEYWORDS = ["uber"]
PATHAO_KEYWORDS = ["pathao"]
TELECOM_KEYWORDS = ["grameenphone", "gp", "robi", "banglalink", "airtel",
                    "teletalk", "16800", "16222", "16616", "16746", "16400"]

AMOUNT_RE = re.compile(r'(?:Tk\.?|BDT|Taka)\s*[:\.]?\s*([\d,]+\.?\d*)', re.I)
BALANCE_RE = re.compile(r'(?:balance|bal|remaining)[:\s]*(?:Tk\.?|BDT)?\s*([\d,]+\.?\d*)', re.I)
TXN_ID_RE = re.compile(r'(?:TrxID|Txn|Transaction\s*(?:ID|No))[:\s]*([A-Za-z0-9]+)', re.I)
PHONE_RE = re.compile(r'01[3-9]\d{8}')


def extract_amount(body):
    m = AMOUNT_RE.search(body)
    return m.group(1).replace(",", "") if m else ""

def extract_balance(body):
    m = BALANCE_RE.search(body)
    return m.group(1).replace(",", "") if m else ""

def extract_txn_id(body):
    m = TXN_ID_RE.search(body)
    return m.group(1) if m else ""

def extract_phone(body):
    m = PHONE_RE.search(body)
    return m.group(0) if m else ""

def detect_mfs_type(body):
    u = body.upper()
    if "CASH IN" in u: return "CASH_IN"
    if "CASH OUT" in u: return "CASH_OUT"
    if "SEND MONEY" in u or "SENT" in u: return "SEND_MONEY"
    if "RECEIVED" in u or "RECEIVE" in u: return "RECEIVE_MONEY"
    if "BILL" in u: return "BILL_PAY"
    if "MERCHANT" in u: return "MERCHANT_PAYMENT"
    if "PAYMENT" in u or "PAY" in u: return "PAYMENT"
    if "RECHARGE" in u: return "MOBILE_RECHARGE"
    if "ADD MONEY" in u: return "ADD_MONEY"
    if "WITHDRAW" in u: return "WITHDRAW"
    if "SALARY" in u: return "SALARY"
    if "REMITTANCE" in u: return "REMITTANCE"
    return "OTHER"

def detect_ride_type(body):
    u = body.upper()
    if "COMPLETED" in u or "TRIP" in u: return "TRIP_COMPLETED"
    if "CANCEL" in u: return "CANCELLED"
    if "PROMO" in u or "DISCOUNT" in u: return "PROMO"
    if "OTP" in u or "CODE" in u: return "VERIFICATION"
    if "FOOD" in u or "DELIVERY" in u: return "DELIVERY"
    return "OTHER"

def detect_recharge_type(body):
    u = body.upper()
    if "RECHARGE" in u or "TOP-UP" in u or "TOPUP" in u: return "RECHARGE"
    if "BUNDLE" in u or "PACK" in u or "INTERNET" in u: return "BUNDLE_PURCHASE"
    if "BONUS" in u: return "BONUS"
    if "EXPIRE" in u: return "EXPIRY_NOTICE"
    if "BALANCE" in u: return "BALANCE_INFO"
    return "OTHER"

def detect_operator(addr, body):
    combined = (addr + " " + body).upper()
    if "GP" in combined or "GRAMEENPHONE" in combined: return "Grameenphone"
    if "ROBI" in combined: return "Robi"
    if "BANGLALINK" in combined: return "Banglalink"
    if "AIRTEL" in combined: return "Airtel"
    if "TELETALK" in combined: return "Teletalk"
    return "Unknown"

def is_telecom_sms(addr, body):
    combined = (addr + " " + body).upper()
    for kw in TELECOM_KEYWORDS:
        if kw.upper() in combined:
            return True
    if any(w in combined for w in ["RECHARGE", "TOP-UP", "TOPUP", "RECHARGED", "BUNDLE", "PACK"]):
        return True
    return False


def collect_sms_data(device=None):
    """Pull all SMS via content provider and parse bKash/Nagad/Telecom/Uber/Pathao."""
    print("\n📱 Collecting SMS data via ADB...")

    raw = adb_cmd("content query --uri content://sms --projection address:body:date:type", device)
    if not raw:
        print("  No SMS access. Trying with root...")
        raw = adb_cmd("su -c 'content query --uri content://sms --projection address:body:date:type'", device)
    if not raw:
        print("  ❌ Cannot access SMS. Need READ_SMS permission from the app.")
        return [], [], []

    mobile_money = []
    telecom_usage = []
    ride_hailing = []

    lines = raw.split("\n")
    print(f"  Found {len(lines)} SMS messages, analyzing...")

    for line in lines:
        if not line.startswith("Row:"):
            continue

        # Parse content provider output: Row: 0 address=xxx, body=xxx, date=xxx, type=x
        addr_m = re.search(r'address=(.*?),\s*body=', line)
        body_m = re.search(r'body=(.*?),\s*date=', line)
        date_m = re.search(r'date=(\d+)', line)
        type_m = re.search(r'type=(\d+)', line)

        if not addr_m or not body_m:
            continue

        address = addr_m.group(1).strip()
        body = body_m.group(1).strip()
        date_ms = date_m.group(1) if date_m else ""
        sms_type = type_m.group(1) if type_m else "0"

        try:
            timestamp = datetime.fromtimestamp(int(date_ms) / 1000).strftime("%Y-%m-%d %H:%M:%S")
        except:
            timestamp = date_ms

        addr_upper = address.upper()
        body_upper = body.upper()

        # bKash
        if any(kw.upper() in addr_upper for kw in BKASH_KEYWORDS) or "BKASH" in body_upper:
            mobile_money.append({
                "provider": "bKash",
                "txn_type": detect_mfs_type(body),
                "amount": extract_amount(body),
                "balance": extract_balance(body),
                "txn_id": extract_txn_id(body),
                "counter_party": extract_phone(body),
                "sender": address,
                "raw_sms": body[:500],
                "timestamp": timestamp
            })
        # Nagad
        elif any(kw.upper() in addr_upper for kw in NAGAD_KEYWORDS) or "NAGAD" in body_upper:
            mobile_money.append({
                "provider": "Nagad",
                "txn_type": detect_mfs_type(body),
                "amount": extract_amount(body),
                "balance": extract_balance(body),
                "txn_id": extract_txn_id(body),
                "counter_party": extract_phone(body),
                "sender": address,
                "raw_sms": body[:500],
                "timestamp": timestamp
            })
        # Uber
        elif any(kw.upper() in addr_upper for kw in UBER_KEYWORDS) or "UBER" in body_upper:
            ride_hailing.append({
                "provider": "Uber",
                "ride_type": detect_ride_type(body),
                "amount": extract_amount(body),
                "trip_details": body[:200],
                "sender": address,
                "timestamp": timestamp
            })
        # Pathao
        elif any(kw.upper() in addr_upper for kw in PATHAO_KEYWORDS) or "PATHAO" in body_upper:
            ride_hailing.append({
                "provider": "Pathao",
                "ride_type": detect_ride_type(body),
                "amount": extract_amount(body),
                "trip_details": body[:200],
                "sender": address,
                "timestamp": timestamp
            })
        # Telecom
        elif is_telecom_sms(address, body):
            telecom_usage.append({
                "operator": detect_operator(address, body),
                "recharge_type": detect_recharge_type(body),
                "amount": extract_amount(body),
                "balance": extract_balance(body),
                "sender": address,
                "raw_sms": body[:500],
                "timestamp": timestamp
            })

    print(f"  ✅ bKash/Nagad: {len(mobile_money)}, Telecom: {len(telecom_usage)}, Uber/Pathao: {len(ride_hailing)}")
    return mobile_money, telecom_usage, ride_hailing


# ═══════════════════════════════════════════════════════════════════════════
# 2. DEVICE INFO
# ═══════════════════════════════════════════════════════════════════════════

def collect_device_info(device_id, device=None):
    """Collect device metadata via ADB shell commands."""
    print("\n📱 Collecting device info...")

    brand = adb_cmd("getprop ro.product.brand", device)
    model = adb_cmd("getprop ro.product.model", device)
    manufacturer = adb_cmd("getprop ro.product.manufacturer", device)
    device_name = adb_cmd("getprop ro.product.device", device)
    hardware = adb_cmd("getprop ro.hardware", device)
    os_version = adb_cmd("getprop ro.build.version.release", device)
    api_level = adb_cmd("getprop ro.build.version.sdk", device)
    security_patch = adb_cmd("getprop ro.build.version.security_patch", device)
    build_fingerprint = adb_cmd("getprop ro.build.fingerprint", device)

    # Uptime
    uptime_raw = adb_cmd("cat /proc/uptime", device)
    uptime_days = "0"
    try:
        uptime_secs = float(uptime_raw.split()[0])
        uptime_days = str(int(uptime_secs / 86400))
    except:
        pass

    # Root detection
    is_rooted = "NO"
    root_paths = ["/system/xbin/su", "/system/bin/su", "/sbin/su",
                  "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
                  "/system/app/Magisk.apk"]
    for p in root_paths:
        check = adb_cmd(f"ls {p}", device)
        if check and "No such file" not in check and "not found" not in check:
            is_rooted = "YES"
            break
    # Also check su command
    su_check = adb_cmd("which su", device)
    if su_check and "/su" in su_check:
        is_rooted = "YES"
    # Check build tags
    build_tags = adb_cmd("getprop ro.build.tags", device)
    if build_tags and "test-keys" in build_tags:
        is_rooted = "YES"

    # SIM info for swap count
    sim_state = adb_cmd("getprop gsm.sim.state", device)
    sim_operator = adb_cmd("getprop gsm.sim.operator.alpha", device)

    # Battery
    battery_raw = adb_cmd("dumpsys battery", device)
    battery_level = ""
    battery_status = ""
    for line in battery_raw.split("\n"):
        if "level:" in line:
            battery_level = line.split(":")[-1].strip()
        if "status:" in line:
            s = line.split(":")[-1].strip()
            status_map = {"1": "unknown", "2": "charging", "3": "discharging",
                          "4": "not_charging", "5": "full"}
            battery_status = status_map.get(s, s)
    battery_info = f"{battery_level}%, {battery_status}"

    # RAM
    meminfo = adb_cmd("cat /proc/meminfo", device)
    total_ram = ""
    avail_ram = ""
    for line in meminfo.split("\n"):
        if "MemTotal:" in line:
            try:
                kb = int(re.search(r'(\d+)', line).group(1))
                total_ram = f"{kb // 1024}MB"
            except: pass
        if "MemAvailable:" in line:
            try:
                kb = int(re.search(r'(\d+)', line).group(1))
                avail_ram = f"{kb // 1024}MB"
            except: pass
    ram_info = f"{total_ram} total, {avail_ram} available"

    # Storage
    storage_raw = adb_cmd("df /data", device)
    storage_info = "unknown"
    try:
        lines = storage_raw.strip().split("\n")
        if len(lines) >= 2:
            parts = lines[1].split()
            if len(parts) >= 4:
                total_kb = int(parts[1]) if parts[1].isdigit() else 0
                avail_kb = int(parts[3]) if parts[3].isdigit() else 0
                storage_info = f"{total_kb // (1024*1024)}GB total, {avail_kb // (1024*1024)}GB free"
    except: pass

    # Screen
    screen_raw = adb_cmd("wm size", device)
    density_raw = adb_cmd("wm density", device)
    screen_info = screen_raw.replace("Physical size: ", "") + " " + density_raw.replace("Physical density: ", "") + "dpi"

    # Network
    network_type = adb_cmd("getprop gsm.network.type", device) or "unknown"

    # Timezone & Language
    timezone = adb_cmd("getprop persist.sys.timezone", device)
    language = adb_cmd("getprop persist.sys.language", device) or adb_cmd("getprop ro.product.locale", device)

    # First boot time (approximation)
    first_boot = adb_cmd("stat -c %Y /data", device) or ""

    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    info = [{
        "device_id": device_id,
        "brand": brand,
        "model": model,
        "manufacturer": manufacturer,
        "device": device_name,
        "hardware": hardware,
        "os_version": os_version,
        "api_level": api_level,
        "security_patch": security_patch,
        "build_fingerprint": build_fingerprint,
        "first_install_time": first_boot,
        "uptime_days": uptime_days,
        "is_rooted": is_rooted,
        "sim_swap_count": "0",  # Will be updated from sim_history data
        "factory_reset_indicator": "0",
        "screen_info": screen_info,
        "ram_info": ram_info,
        "storage_info": storage_info,
        "battery_info": battery_info,
        "network_type": network_type,
        "timezone": timezone,
        "language": language,
        "country": "",
        "timestamp": timestamp
    }]

    print(f"  ✅ {brand} {model}, Android {os_version}, Root: {is_rooted}")
    return info


# ═══════════════════════════════════════════════════════════════════════════
# 3. INSTALLED APPS
# ═══════════════════════════════════════════════════════════════════════════

TRACKED_APPS = {
    # MFS
    "com.bKash.customerapp": ("bKash", "MFS"),
    "com.konasl.nagad": ("Nagad", "MFS"),
    "com.dbbl.mbs.apps.main": ("Rocket (DBBL)", "MFS"),
    "bd.com.upay": ("Upay", "MFS"),
    "com.progoti.tallykhata": ("TallyKhata", "MFS"),
    # Banking
    "com.ibl.ebanking.android": ("City Bank", "BANKING"),
    "com.brac.bank.asBankApp": ("BRAC Bank", "BANKING"),
    "com.dutchbangla.nexus": ("DBBL Nexus", "BANKING"),
    "com.ebl.skybanking": ("EBL Sky Banking", "BANKING"),
    # Ride
    "com.ubercab": ("Uber", "RIDE"),
    "com.pathao.user": ("Pathao", "RIDE"),
    "com.obhai.user": ("Obhai", "RIDE"),
    # E-commerce
    "com.daraz.android": ("Daraz", "ECOMMERCE"),
    "com.chaldal.poached": ("Chaldal", "ECOMMERCE"),
    # Food
    "com.global.foodpanda.android": ("Foodpanda", "FOOD_DELIVERY"),
    "com.pathao.food": ("Pathao Food", "FOOD_DELIVERY"),
    "com.hungrynaki.android": ("HungryNaki", "FOOD_DELIVERY"),
    "com.shohoz.food": ("Shohoz Food", "FOOD_DELIVERY"),
    # Social
    "com.whatsapp": ("WhatsApp", "SOCIAL"),
    "com.facebook.orca": ("Messenger", "SOCIAL"),
    "com.facebook.katana": ("Facebook", "SOCIAL"),
    "com.imo.android.imoim": ("IMO", "SOCIAL"),
    "com.viber.voip": ("Viber", "SOCIAL"),
    "org.telegram.messenger": ("Telegram", "SOCIAL"),
    # Utility
    "com.google.android.apps.maps": ("Google Maps", "UTILITY"),
    "com.google.android.gm": ("Gmail", "UTILITY"),
    "com.linkedin.android": ("LinkedIn", "PROFESSIONAL"),
    # Telecom
    "com.grameenphone.gp": ("My GP", "TELECOM"),
    "com.robi.myrobi": ("My Robi", "TELECOM"),
    "com.banglalink.mybl": ("My Banglalink", "TELECOM"),
}

def collect_installed_apps(device=None):
    """Collect ALL real installed user apps from the phone with details."""
    print("\n📱 Collecting installed apps...")

    # Get ALL user-installed packages (real apps on phone)
    raw = adb_cmd("pm list packages -3", device)
    installed_packages = []
    for line in raw.split("\n"):
        if line.startswith("package:"):
            installed_packages.append(line.replace("package:", "").strip())

    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    apps_data = []

    # Categorize known packages
    def categorize(pkg):
        pkg_lower = pkg.lower()
        # MFS
        if any(k in pkg_lower for k in ["bkash", "nagad", "rocket", "upay", "mcash", "tallykhata", "cellfin"]):
            return "MFS"
        # Banking
        if any(k in pkg_lower for k in ["bank", "ebl", "dbbl", "nexus", "islami", "ucb", "brac"]):
            return "BANKING"
        # Ride
        if any(k in pkg_lower for k in ["uber", "pathao", "obhai"]):
            return "RIDE"
        # E-commerce
        if any(k in pkg_lower for k in ["daraz", "chaldal", "pickaboo", "othoba", "evaly", "alibaba", "amazon"]):
            return "ECOMMERCE"
        # Food
        if any(k in pkg_lower for k in ["foodpanda", "hungrynaki", "shohoz.food", "pathao.food"]):
            return "FOOD_DELIVERY"
        # Social
        if any(k in pkg_lower for k in ["whatsapp", "facebook", "messenger", "imo.", "viber", "telegram", "tiktok", "instagram", "snapchat", "twitter"]):
            return "SOCIAL"
        # Telecom
        if any(k in pkg_lower for k in ["grameenphone", "robi", "banglalink", "airtel", "teletalk"]):
            return "TELECOM"
        # Games
        if any(k in pkg_lower for k in ["game", "pubg", "freefire", "clash", "candy"]):
            return "GAME"
        # Utility
        if any(k in pkg_lower for k in ["google", "chrome", "youtube", "gmail", "maps", "drive", "office", "microsoft"]):
            return "UTILITY"
        # Professional
        if any(k in pkg_lower for k in ["linkedin", "indeed", "bdjobs"]):
            return "PROFESSIONAL"
        return "OTHER"

    print(f"  Found {len(installed_packages)} user apps, getting details...")

    for pkg in installed_packages:
        # Get real app label and details via dumpsys
        dump = adb_cmd(f"dumpsys package {pkg}", device)

        app_name = pkg  # fallback to package name
        version = ""
        install_date = ""
        last_update = ""

        for line in dump.split("\n"):
            line = line.strip()
            if "versionName=" in line and not version:
                version = line.split("versionName=")[-1].strip()
            if "firstInstallTime=" in line and not install_date:
                install_date = line.split("firstInstallTime=")[-1].strip()
            if "lastUpdateTime=" in line and not last_update:
                last_update = line.split("lastUpdateTime=")[-1].strip()

        # Get the real app label using cmd package
        label_raw = adb_cmd(f"cmd package resolve-activity --brief {pkg}", device)
        # Try to extract human-readable name from the known apps map
        if pkg in TRACKED_APPS:
            app_name = TRACKED_APPS[pkg][0]
        else:
            # Use the last part of package name, cleaned up
            parts = pkg.split(".")
            if len(parts) >= 2:
                app_name = parts[-1].replace("_", " ").title()
                # If last part is too generic, use last 2 parts
                if app_name.lower() in ["app", "android", "main", "lite"]:
                    app_name = parts[-2].replace("_", " ").title() + " " + app_name

        category = categorize(pkg)

        apps_data.append({
            "package_name": pkg,
            "app_name": app_name,
            "category": category,
            "version": version,
            "install_date": install_date,
            "last_update": last_update,
            "status": "INSTALLED",
            "timestamp": timestamp
        })

    # Also check which KEY tracked apps are missing (credit-relevant signal)
    key_tracked = {
        "com.bKash.customerapp": "bKash",
        "com.konasl.nagad": "Nagad",
        "com.ubercab": "Uber",
        "com.pathao.user": "Pathao",
    }
    installed_set = set(installed_packages)
    for pkg, name in key_tracked.items():
        if pkg not in installed_set:
            apps_data.append({
                "package_name": pkg,
                "app_name": name,
                "category": "KEY_MISSING",
                "version": "",
                "install_date": "",
                "last_update": "",
                "status": "NOT_INSTALLED",
                "timestamp": timestamp
            })

    # Summary
    system_raw = adb_cmd("pm list packages -s", device)
    total_system = len([l for l in system_raw.split("\n") if l.startswith("package:")])
    total_user = len(installed_packages)

    apps_data.append({
        "package_name": "_summary_",
        "app_name": "App Summary",
        "category": "SUMMARY",
        "version": "",
        "install_date": str(total_user + total_system),
        "last_update": f"system:{total_system},user:{total_user}",
        "status": "SUMMARY",
        "timestamp": timestamp
    })

    installed_count = sum(1 for a in apps_data if a["status"] == "INSTALLED")
    print(f"  ✅ {installed_count} tracked apps found, {total_user} user apps, {total_system} system apps")
    return apps_data


# ═══════════════════════════════════════════════════════════════════════════
# 4. BEHAVIORAL ANALYSIS (from call logs + SMS)
# ═══════════════════════════════════════════════════════════════════════════

def collect_behavior_scores(device=None):
    """Analyze call/SMS patterns for behavioral scoring."""
    print("\n📱 Computing behavioral analysis...")

    # Get call logs
    call_raw = adb_cmd(
        "content query --uri content://call_log/calls --projection number:type:date:duration",
        device
    )

    total_calls = 0
    incoming = 0
    outgoing = 0
    missed = 0
    total_duration = 0
    night_calls = 0
    weekend_calls = 0
    unique_contacts = set()
    daily_counts = defaultdict(int)

    if call_raw:
        for line in call_raw.split("\n"):
            if not line.startswith("Row:"):
                continue
            total_calls += 1

            num_m = re.search(r'number=(.*?),', line)
            type_m = re.search(r'type=(\d+)', line)
            date_m = re.search(r'date=(\d+)', line)
            dur_m = re.search(r'duration=(\d+)', line)

            if num_m:
                unique_contacts.add(num_m.group(1).strip())

            call_type = int(type_m.group(1)) if type_m else 0
            if call_type == 1: incoming += 1
            elif call_type == 2: outgoing += 1
            elif call_type == 3: missed += 1

            if dur_m:
                total_duration += int(dur_m.group(1))

            if date_m:
                try:
                    dt = datetime.fromtimestamp(int(date_m.group(1)) / 1000)
                    if dt.hour >= 22 or dt.hour < 6:
                        night_calls += 1
                    if dt.weekday() in [4, 5]:  # Fri, Sat (BD weekend)
                        weekend_calls += 1
                    daily_counts[dt.strftime("%Y-%m-%d")] += 1
                except:
                    pass

    # Get SMS counts
    sms_raw = adb_cmd(
        "content query --uri content://sms --projection address:type",
        device
    )
    total_sms = 0
    sent_sms = 0
    received_sms = 0
    unique_sms_contacts = set()

    if sms_raw:
        for line in sms_raw.split("\n"):
            if not line.startswith("Row:"):
                continue
            total_sms += 1

            addr_m = re.search(r'address=(.*?),', line)
            type_m = re.search(r'type=(\d+)', line)

            if addr_m:
                unique_sms_contacts.add(addr_m.group(1).strip())
            sms_type = int(type_m.group(1)) if type_m else 0
            if sms_type == 1: received_sms += 1
            elif sms_type == 2: sent_sms += 1

    # Compute scores
    call_regularity = 0
    if daily_counts:
        counts = list(daily_counts.values())
        mean = sum(counts) / len(counts)
        variance = sum((c - mean) ** 2 for c in counts) / len(counts)
        std_dev = variance ** 0.5
        call_regularity = std_dev / mean if mean > 0 else 0

    in_out_ratio = incoming / outgoing if outgoing > 0 else 0
    night_ratio = night_calls / total_calls if total_calls > 0 else 0
    weekend_ratio = weekend_calls / total_calls if total_calls > 0 else 0
    avg_duration = total_duration / total_calls if total_calls > 0 else 0
    contact_diversity = len(unique_contacts) / total_calls if total_calls > 0 else 0

    all_contacts = unique_contacts | unique_sms_contacts
    network_size = len(all_contacts)

    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    scores = [{
        "total_calls": total_calls,
        "incoming_calls": incoming,
        "outgoing_calls": outgoing,
        "missed_calls": missed,
        "total_duration": total_duration,
        "night_calls": night_calls,
        "weekend_calls": weekend_calls,
        "unique_call_contacts": len(unique_contacts),
        "call_regularity": f"{call_regularity:.2f}",
        "in_out_ratio": f"{in_out_ratio:.2f}",
        "night_ratio": f"{night_ratio:.3f}",
        "weekend_ratio": f"{weekend_ratio:.3f}",
        "avg_call_duration": f"{avg_duration:.1f}",
        "contact_diversity": f"{contact_diversity:.3f}",
        "total_sms": total_sms,
        "sent_sms": sent_sms,
        "received_sms": received_sms,
        "unique_sms_contacts": len(unique_sms_contacts),
        "network_size": network_size,
        "unique_locations": 0,
        "total_mfs_txns": 0,
        "total_mfs_volume": "0",
        "total_recharges": 0,
        "total_recharge_amount": "0",
        "mfs_activity_score": "0",
        "recharge_frequency": "0",
        "timestamp": timestamp
    }]

    print(f"  ✅ Calls: {total_calls} (In:{incoming}/Out:{outgoing}/Miss:{missed})")
    print(f"     SMS: {total_sms} (Sent:{sent_sms}/Recv:{received_sms})")
    print(f"     Network: {network_size} contacts, Regularity: {call_regularity:.2f}")
    return scores


# ═══════════════════════════════════════════════════════════════════════════
# 5. REAL LOCATION DATA (IP + Cell Tower + WiFi)
# ═══════════════════════════════════════════════════════════════════════════

def collect_location_data(device_id, device=None):
    """Collect real location data using IP geolocation + cell tower info from phone."""
    print("\n📍 Collecting location data...")

    locations = []
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    # Method 1: IP-based geolocation from phone's own network
    ip_raw = adb_cmd("echo -e 'GET /json HTTP/1.1\\nHost: ip-api.com\\nConnection: close\\n\\n' | toybox nc ip-api.com 80", device)
    ip_data = None
    for line in ip_raw.split("\n"):
        line = line.strip()
        if line.startswith("{") and "lat" in line:
            try:
                ip_data = json.loads(line)
            except:
                pass

    if ip_data and ip_data.get("status") == "success":
        locations.append({
            "latitude": ip_data["lat"],
            "longitude": ip_data["lon"],
            "accuracy": 5000.0,  # city-level ~5km
            "address": f"{ip_data.get('city', '')}, {ip_data.get('regionName', '')}, {ip_data.get('country', '')}",
            "timestamp": timestamp,
            "source": "IP_GEOLOCATION",
            "isp": ip_data.get("isp", ""),
            "device_id": device_id
        })
        print(f"  ✅ IP Location: {ip_data['city']}, {ip_data['lat']}, {ip_data['lon']}")

    # Method 2: Cell tower data for network-based location
    cell_raw = adb_cmd("dumpsys telephony.registry", device)
    cells_parsed = []
    for m in re.finditer(r'CellIdentityLte:\{[^}]+\}', cell_raw):
        block = m.group(0)
        ci = re.search(r'mCi=(\d+)', block)
        tac = re.search(r'mTac=(\d+)', block)
        mcc = re.search(r'mMcc=(\d+)', block)
        mnc = re.search(r'mMnc=(\d+)', block)
        name = re.search(r'mAlphaLong=(\w+)', block)
        if ci and tac and ci.group(1) != "2147483647":
            cells_parsed.append({
                "cell_id": ci.group(1),
                "tac": tac.group(1),
                "mcc": mcc.group(1) if mcc else "",
                "mnc": mnc.group(1) if mnc else "",
                "operator": name.group(1) if name else ""
            })

    # Deduplicate cells
    seen_cells = set()
    unique_cells = []
    for c in cells_parsed:
        k = c["cell_id"] + "-" + c["tac"]
        if k not in seen_cells:
            seen_cells.add(k)
            unique_cells.append(c)

    if unique_cells:
        # Use IP location as base, add cell tower info
        base_lat = ip_data["lat"] if ip_data else 23.8103
        base_lon = ip_data["lon"] if ip_data else 90.4125
        locations.append({
            "latitude": base_lat,
            "longitude": base_lon,
            "accuracy": 1000.0,  # cell-level ~1km
            "address": f"Cell Tower: {unique_cells[0]['operator']} CID={unique_cells[0]['cell_id']} TAC={unique_cells[0]['tac']}",
            "timestamp": timestamp,
            "source": "CELL_TOWER",
            "cell_towers": len(unique_cells),
            "device_id": device_id
        })
        print(f"  ✅ Cell Towers: {len(unique_cells)} towers detected ({unique_cells[0]['operator']})")

    # Method 3: WiFi BSSID info
    wifi_raw = adb_cmd("dumpsys wifi", device)
    ssid_match = re.search(r'mWifiInfo SSID: "?([^",]+)"?', wifi_raw)
    bssid_match = re.search(r'BSSID: ([\da-f:]+)', wifi_raw, re.I)
    freq_match = re.search(r'Frequency: (\d+)', wifi_raw)
    link_match = re.search(r'Link speed: (\d+)', wifi_raw)
    if ssid_match:
        wifi_loc = {
            "latitude": ip_data["lat"] if ip_data else 23.8103,
            "longitude": ip_data["lon"] if ip_data else 90.4125,
            "accuracy": 50.0,  # WiFi ~50m
            "address": f"WiFi: {ssid_match.group(1)}",
            "timestamp": timestamp,
            "source": "WIFI",
            "wifi_ssid": ssid_match.group(1),
            "wifi_bssid": bssid_match.group(1) if bssid_match else "",
            "device_id": device_id
        }
        locations.append(wifi_loc)
        print(f"  ✅ WiFi: {ssid_match.group(1)}" + (f" BSSID={bssid_match.group(1)}" if bssid_match else ""))

    print(f"  📍 Total: {len(locations)} location records")
    return locations


# ═══════════════════════════════════════════════════════════════════════════
# 6. REAL SIM DATA (from dumpsys isub + telephony)
# ═══════════════════════════════════════════════════════════════════════════

def collect_sim_data(device_id, device=None):
    """Collect real SIM card data: current SIMs + state change history."""
    print("\n📱 Collecting SIM card data...")

    sim_records = []
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    # Parse subscription info
    isub_raw = adb_cmd("dumpsys isub", device)

    # Extract active subscriptions - split by SubscriptionInfoInternal marker
    subs = []
    parts = isub_raw.split("SubscriptionInfoInternal:")
    for part in parts[1:]:  # skip first (before any marker)
        # Take everything up to the closing bracket
        block = part
        sub_id = re.search(r'id=(\d+)', block)
        iccid = re.search(r'iccId=([A-Za-z0-9*]+)', block)
        slot = re.search(r'simSlotIndex=(\d+)', block)
        carrier = re.search(r'carrierName=(\w+)', block)
        display = re.search(r'displayName=(\w+)', block)
        mcc = re.search(r'\bmcc=(\d+)', block)
        mnc = re.search(r'\bmnc=(\d+)', block)
        country = re.search(r'countryIso=(\w+)', block)
        imsi_match = re.search(r'imsi=([0-9*]+)', block)

        if iccid:
            subs.append({
                "sub_id": sub_id.group(1) if sub_id else "",
                "iccid": iccid.group(1),
                "slot": slot.group(1) if slot else "",
                "carrier": carrier.group(1) if carrier else "",
                "display_name": display.group(1) if display else "",
                "mcc": mcc.group(1) if mcc else "",
                "mnc": mnc.group(1) if mnc else "",
                "country": country.group(1) if country else "",
                "imsi": imsi_match.group(1) if imsi_match else ""
            })

    # Deduplicate by iccid
    seen_iccid = set()
    unique_subs = []
    for s in subs:
        if s["iccid"] not in seen_iccid:
            seen_iccid.add(s["iccid"])
            unique_subs.append(s)

    # Get current SIM states
    sim_states_raw = adb_cmd("getprop gsm.sim.state", device)
    sim_states = sim_states_raw.split(",") if sim_states_raw else []
    operators_raw = adb_cmd("getprop gsm.operator.alpha", device)
    operators = operators_raw.split(",") if operators_raw else []

    # Current SIM snapshot for each slot
    for i, sub in enumerate(unique_subs):
        state = sim_states[i].strip() if i < len(sim_states) else "UNKNOWN"
        operator = operators[i].strip() if i < len(operators) else sub["carrier"]
        phone_raw = adb_cmd(f"getprop gsm.sim.operator.numeric", device)
        phones = phone_raw.split(",") if phone_raw else []

        sim_records.append({
            "old_iccid": "",
            "new_iccid": sub["iccid"],
            "phone_number": "",
            "carrier": operator or sub["carrier"],
            "sim_slot": sub["slot"],
            "sim_state": state,
            "mcc": sub["mcc"],
            "mnc": sub["mnc"],
            "country": sub["country"],
            "imsi": sub["imsi"],
            "event_type": "CURRENT_SIM",
            "timestamp": timestamp,
            "device_id": device_id
        })
        print(f"  ✅ Slot {sub['slot']}: {operator or sub['carrier']} (ICCID: {sub['iccid'][:10]}..., State: {state})")

    # Parse SIM state change history from logs
    sim_change_count = 0
    log_section = False
    for line in isub_raw.split("\n"):
        line = line.strip()
        if "Local log:" in line:
            log_section = True
            continue
        if not log_section:
            continue
        if not line or line.startswith("Subscription") or line.startswith("All ") or line.startswith("Embedded"):
            log_section = False
            continue

        # Parse log entries like "2026-03-17T18:37:54.644853 - updateSimState: slot 1 UNKNOWN"
        state_match = re.match(r'(\d{4}-\d{2}-\d{2}T[\d:.]+)\s*-\s*updateSimState:\s*slot\s*(\d+)\s*(\w+)', line)
        if state_match:
            ts_raw = state_match.group(1).split(".")[0].replace("T", " ")
            slot_num = state_match.group(2)
            new_state = state_match.group(3)

            # Find which SIM is in this slot
            slot_sub = next((s for s in unique_subs if s["slot"] == slot_num), None)
            carrier_name = slot_sub["carrier"] if slot_sub else f"SIM_{slot_num}"
            iccid_val = slot_sub["iccid"] if slot_sub else ""

            if new_state in ["UNKNOWN", "NOT_READY"]:
                # SIM removed or restarting
                sim_records.append({
                    "old_iccid": iccid_val,
                    "new_iccid": "",
                    "phone_number": "",
                    "carrier": carrier_name,
                    "sim_slot": slot_num,
                    "sim_state": new_state,
                    "mcc": slot_sub["mcc"] if slot_sub else "",
                    "mnc": slot_sub["mnc"] if slot_sub else "",
                    "country": slot_sub["country"] if slot_sub else "",
                    "imsi": "",
                    "event_type": "SIM_REMOVED" if new_state == "NOT_READY" else "SIM_STATE_CHANGE",
                    "timestamp": ts_raw,
                    "device_id": device_id
                })
                sim_change_count += 1
            elif new_state in ["READY", "LOADED"]:
                # SIM inserted/loaded
                sim_records.append({
                    "old_iccid": "",
                    "new_iccid": iccid_val,
                    "phone_number": "",
                    "carrier": carrier_name,
                    "sim_slot": slot_num,
                    "sim_state": new_state,
                    "mcc": slot_sub["mcc"] if slot_sub else "",
                    "mnc": slot_sub["mnc"] if slot_sub else "",
                    "country": slot_sub["country"] if slot_sub else "",
                    "imsi": slot_sub["imsi"] if slot_sub else "",
                    "event_type": "SIM_LOADED" if new_state == "LOADED" else "SIM_READY",
                    "timestamp": ts_raw,
                    "device_id": device_id
                })
                sim_change_count += 1

        # Parse default sub changes
        default_match = re.match(r'(\d{4}-\d{2}-\d{2}T[\d:.]+)\s*-\s*updateDefaultSubId:.*from\s+(-?\d+)\s+to\s+(-?\d+)', line)
        if default_match:
            ts_raw = default_match.group(1).split(".")[0].replace("T", " ")
            from_sub = default_match.group(2)
            to_sub = default_match.group(3)
            sim_records.append({
                "old_iccid": f"subId={from_sub}",
                "new_iccid": f"subId={to_sub}",
                "phone_number": "",
                "carrier": "System",
                "sim_slot": "",
                "sim_state": "DEFAULT_CHANGE",
                "mcc": "",
                "mnc": "",
                "country": "",
                "imsi": "",
                "event_type": "DEFAULT_SUB_CHANGE",
                "timestamp": ts_raw,
                "device_id": device_id
            })

    print(f"  📱 Total: {len(sim_records)} SIM records ({len(unique_subs)} active SIMs, {sim_change_count} state changes)")
    return sim_records


# ═══════════════════════════════════════════════════════════════════════════
# 7. LOCATION DWELL (from app's existing DB)
# ═══════════════════════════════════════════════════════════════════════════

def collect_location_dwell(device=None):
    """Try to read location data from the app's SQLite DB via ADB."""
    print("\n📱 Collecting location dwell data...")

    # Try to read from the app's database
    db_path = "/data/data/com.datacollector/databases/datacollector.db"
    query = "SELECT latitude,longitude,accuracy,timestamp,address FROM location ORDER BY timestamp DESC LIMIT 50"

    raw = adb_cmd(f"run-as com.datacollector sqlite3 {db_path} \"{query}\"", device)
    if not raw or "error" in raw.lower():
        # Try with su
        raw = adb_cmd(f"su -c 'sqlite3 {db_path} \"{query}\"'", device)

    if not raw or "error" in raw.lower():
        print("  ⚠️  Cannot read location DB directly. Location dwell relies on the Android app.")
        return []

    dwell_data = []
    prev_lat, prev_lng, prev_time = None, None, None

    for line in raw.split("\n"):
        parts = line.split("|")
        if len(parts) < 5:
            continue

        try:
            lat = float(parts[0])
            lng = float(parts[1])
            acc = float(parts[2]) if parts[2] else 0
            ts = parts[3]
            addr = parts[4]

            # Compute dwell
            dwell_minutes = 0
            event_type = "ARRIVAL"
            if prev_lat is not None:
                # Simple distance check (~100m threshold)
                dlat = abs(lat - prev_lat) * 111000
                dlng = abs(lng - prev_lng) * 111000
                dist = (dlat**2 + dlng**2) ** 0.5
                if dist < 100:
                    event_type = "DWELL"
                    try:
                        from datetime import datetime as dt2
                        t1 = dt2.strptime(prev_time, "%Y-%m-%d %H:%M:%S")
                        t2 = dt2.strptime(ts, "%Y-%m-%d %H:%M:%S")
                        dwell_minutes = abs(int((t1 - t2).total_seconds() / 60))
                    except:
                        pass

            # Classify time
            loc_type = "OTHER"
            try:
                dt_obj = datetime.strptime(ts, "%Y-%m-%d %H:%M:%S")
                hour = dt_obj.hour
                dow = dt_obj.weekday()
                if hour >= 22 or hour < 6: loc_type = "NIGHT_HOME"
                elif 9 <= hour < 17 and dow not in [4, 5]: loc_type = "WORK_HOURS"
                elif dow in [4, 5]: loc_type = "WEEKEND"
                elif 6 <= hour < 9: loc_type = "MORNING_COMMUTE"
                elif 17 <= hour < 22: loc_type = "EVENING"
            except: pass

            dwell_data.append({
                "latitude": lat,
                "longitude": lng,
                "accuracy": acc,
                "address": addr,
                "timestamp": ts,
                "dwell_minutes": dwell_minutes,
                "location_type": loc_type,
                "visit_count": 1,
                "event_type": event_type
            })

            prev_lat, prev_lng, prev_time = lat, lng, ts
        except:
            continue

    print(f"  ✅ {len(dwell_data)} location records with dwell analysis")
    return dwell_data


# ═══════════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════════

def main():
    print("=" * 60)
    print("  ADB DATA COLLECTOR — Phone → Backend")
    print("=" * 60)

    # Check ADB connection
    device = None
    if len(sys.argv) > 2 and sys.argv[1] == "--device":
        device = sys.argv[2]

    devices_raw = adb_cmd_raw(["devices"], device)
    print(f"\nConnected devices:\n{devices_raw}")

    # Get device ID
    device_id = get_device_id(device)
    if not device_id:
        print("❌ No device connected or cannot get Android ID!")
        sys.exit(1)
    print(f"\n📱 Device ID: {device_id}")

    # 1. SMS Analysis (bKash/Nagad/Telecom/Uber/Pathao)
    mobile_money, telecom_usage, ride_hailing = collect_sms_data(device)

    # 2. Device Info
    device_info = collect_device_info(device_id, device)

    # 3. Installed Apps
    installed_apps = collect_installed_apps(device)

    # 4. Behavioral Analysis
    behavior_scores = collect_behavior_scores(device)

    # Update behavior scores with MFS data
    if behavior_scores and mobile_money:
        behavior_scores[0]["total_mfs_txns"] = len(mobile_money)
        total_vol = sum(float(t["amount"]) for t in mobile_money if t["amount"])
        behavior_scores[0]["total_mfs_volume"] = f"{total_vol:.2f}"
        behavior_scores[0]["mfs_activity_score"] = f"{min(len(mobile_money)/100, 1.0):.2f}"
    if behavior_scores and telecom_usage:
        recharges = [t for t in telecom_usage if t["recharge_type"] == "RECHARGE"]
        behavior_scores[0]["total_recharges"] = len(recharges)
        total_rech = sum(float(t["amount"]) for t in recharges if t["amount"])
        behavior_scores[0]["total_recharge_amount"] = f"{total_rech:.2f}"
        behavior_scores[0]["recharge_frequency"] = f"{len(recharges):.1f}"

    # 5. Location Data (IP + Cell Tower + WiFi)
    location_data = collect_location_data(device_id, device)

    # 6. SIM Card Data
    sim_data = collect_sim_data(device_id, device)

    # 7. Location Dwell
    location_dwell = collect_location_dwell(device)

    # Update device info with SIM swap count
    if device_info and sim_data:
        remove_events = sum(1 for s in sim_data if s.get("event_type") in ["SIM_REMOVED", "SIM_STATE_CHANGE"])
        device_info[0]["sim_swap_count"] = str(remove_events)

    # Update behavior scores with location count
    if behavior_scores and location_data:
        behavior_scores[0]["unique_locations"] = len(location_data)

    # ─── Send everything to server ──────────────────────────────────────
    print("\n" + "=" * 60)
    print("  SENDING TO SERVER...")
    print("=" * 60)

    send_to_server("mobile_money", mobile_money, device_id)
    send_to_server("telecom_usage", telecom_usage, device_id)
    send_to_server("ride_hailing", ride_hailing, device_id)
    send_to_server("device_info", device_info, device_id)
    send_to_server("installed_apps", installed_apps, device_id)
    send_to_server("behavior_scores", behavior_scores, device_id)
    send_to_server("location", location_data, device_id)
    send_to_server("sim_history", sim_data, device_id)
    send_to_server("location_dwell", location_dwell, device_id)

    print("\n" + "=" * 60)
    print("  ✅ ALL DONE!")
    print(f"  Check dashboard: https://datacollector-panel.netlify.app")
    print("=" * 60)


if __name__ == "__main__":
    main()
