// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Installation of the dvfel kernel module bundled with this library
 * (dvfp_module_install): the module is loaded with root (su) and installed
 * into the Dune HD firmware's config directory together with a boot script,
 * which the firmware runs as root at every boot (R24: binit.sh, R22: the
 * /system/xbin/dunehd/init service itself). A newer module is never
 * replaced by an older one. A firmware that brings the module itself (Dune
 * HD r25 on: the plugin s928x_dvfel) keeps it: nothing is installed there.
 * On an Amlogic SC2 (S905X4) the SC2 module is installed instead (another
 * kernel, composition by the Dolby Vision core).
 */

#include "dvfelplay.h"

#include "dvfel_sc2_uapi.h"
#include "dvfel_uapi.h"

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>

#include <android/log.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#ifdef DVFP_HAVE_KO
// the module (module_blob.S)
extern "C" const unsigned char dvfp_ko_start[], dvfp_ko_end[];
#endif
#ifdef DVFP_HAVE_KO_SC2
// the SC2 module (module_blob_sc2.S)
extern "C" const unsigned char dvfp_ko_sc2_start[], dvfp_ko_sc2_end[];
#endif

namespace
{
bool IsSc2()
{
  static const bool sc2 = [] {
    char v[PROP_VALUE_MAX] = {};
    return __system_property_get("ro.board.platform", v) > 0 && !strcmp(v, "sc2");
  }();
  return sc2;
}

// the bundled module of this SoC: its version, its image (nullptr: none)
const char* BundledVersion()
{
  return IsSc2() ? DVFEL_SC2_VERSION : DVFEL_VERSION;
}

const unsigned char* BundledKo(size_t& size)
{
  size = 0;
#ifdef DVFP_HAVE_KO_SC2
  if (IsSc2())
  {
    size = dvfp_ko_sc2_end - dvfp_ko_sc2_start;
    return dvfp_ko_sc2_start;
  }
#endif
#ifdef DVFP_HAVE_KO
  if (!IsSc2())
  {
    size = dvfp_ko_end - dvfp_ko_start;
    return dvfp_ko_start;
  }
#endif
  return nullptr;
}
constexpr const char* DVFEL_DEV = "/dev/dvfel";
// the Dune firmware's switch for su (its sud daemon): "" (default) or "1" on
constexpr const char* ROOT_PROP = "persist.vendor.root_access";

// run as root: $1 = the module to install. One module build for all firmware
// versions: their kernel only warns about symbol CRC mismatches ("disagrees
// about version of symbol") and loads it anyway; the warnings go to the log.
constexpr const char* INSTALL_SCRIPT = R"SH(#!/system/bin/sh
KO="$1"
FSP=$(sed -n 's/^FSP=//p' /system/xbin/dunehd/init 2>/dev/null | head -1)
[ -n "$FSP" ] || FSP=/data/data/com.dunehd.app
grep -qs 'config/boot' /system/dunehd/firmware/scripts/binit.sh /system/xbin/dunehd/init ||
  { echo "no Dune HD firmware boot hook (binit.sh, dunehd/init)"; exit 2; }
D=$FSP/config
[ -d "$D" ] || { echo "$D missing"; exit 2; }
echo "firmware $(grep -h firmware_version "$D/last_fw_info.txt" 2>/dev/null), $(getprop ro.product.model), kernel $(uname -r)"
mkdir -p "$D/dvfel" "$D/boot" && chmod 755 "$D/dvfel" "$D/boot" || exit 3
INST="$D/dvfel/dvfel.ko"

# an older module: replaced (not in use while no player plays FEL)
if [ -d /sys/module/dvfel ]; then
  rmmod dvfel || { echo "the loaded module is in use, try again after a reboot"; exit 4; }
fi
b=$(dmesg | grep -c 'dvfel: disagrees about version')
if ! insmod "$KO"; then
  echo "insmod failed:"
  dmesg | grep -i dvfel | tail -3
  [ -f "$INST" ] && insmod "$INST" 2>/dev/null && echo "the installed module is loaded again"
  exit 5
fi
a=$(dmesg | grep -c 'dvfel: disagrees about version')
if [ "$a" -gt "$b" ]; then
  echo "note: symbol versions differ from the build firmware (loaded anyway):"
  dmesg | grep 'dvfel: disagrees about version' | tail -n $((a - b)) | head -10
fi
if ! cmp -s "$KO" "$INST"; then
  cp "$KO" "$INST.tmp" && chmod 644 "$INST.tmp" && mv "$INST.tmp" "$INST" || exit 6
  echo "module installed to $INST"
fi

FC=/sys/module/amvdec_h265_fb_v4l/parameters/decode_timeout_back_fast_check
cat > "$D/boot/dvfel.sh.tmp" <<EOS
#!/system/bin/sh
# Amlogic H.265 decoder: no back-end fast check. When it times out (8-12 ms
# without progress) and the back-end IRQ arrives anyway, vh265_back_irq_cb+0x114
# reads NULL+0xf8 and the kernel panics. The regular 200 ms back-end timeout
# still applies.
[ -f $FC ] && echo 0 > $FC
# Dolby Vision FEL composition kernel module (dvfel)
insmod $INST || exit 1
# ueventd creates the device node asynchronously
i=0
while [ ! -e /dev/dvfel ] && [ \$i -lt 50 ]; do sleep 0.1; i=\$((i + 1)); done
chmod 666 /dev/dvfel
EOS
if cmp -s "$D/boot/dvfel.sh.tmp" "$D/boot/dvfel.sh"; then
  rm -f "$D/boot/dvfel.sh.tmp"
else
  mv "$D/boot/dvfel.sh.tmp" "$D/boot/dvfel.sh" && echo "boot script installed"
fi
chmod 755 "$D/boot/dvfel.sh"
[ -f $FC ] && echo 0 > $FC && echo "H.265 back-end fast check off"
i=0
while [ ! -e /dev/dvfel ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
chmod 666 /dev/dvfel || exit 7
echo "dvfel $(cat /sys/module/dvfel/version 2>/dev/null) ready"
)SH";

void Log(int prio, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
void Log(int prio, const char* fmt, ...)
{
  va_list ap;
  va_start(ap, fmt);
  __android_log_vprint(prio, "dvfelplay", fmt, ap);
  va_end(ap);
}

// runs a shell command; its output lines to the log when tag is set
int Run(const std::string& cmd, const char* tag = nullptr, std::string* out = nullptr)
{
  FILE* p = popen((cmd + " 2>&1").c_str(), "r");
  if (!p)
    return -1;
  char line[256];
  while (fgets(line, sizeof(line), p))
  {
    std::string s(line);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r'))
      s.pop_back();
    if (tag)
      Log(ANDROID_LOG_INFO, "%s: %s", tag, s.c_str());
    if (out)
      *out += s;
  }
  return pclose(p);
}

bool SuWorks()
{
  return Run("timeout 10 su -c true") == 0;
}

std::string ReadLine(const std::string& path)
{
  std::ifstream f(path);
  std::string s;
  std::getline(f, s);
  return s;
}

/*
 * The Dune HD firmware brings the module itself (r25 on) as the plugin
 * s928x_dvfel: at every boot it removes our boot script and loads its own
 * module, unloading any other version. A module installed here would be
 * replaced at the next boot (and installed again, with the root question,
 * at every start): the firmware's module is used as it is.
 */
constexpr const char* FW_PLUGIN = "/system/dunehd/firmware_ext/plugins/s928x_dvfel/dune_plugin.xml";

bool FirmwareModule()
{
  return !IsSc2() && access(FW_PLUGIN, F_OK) == 0;
}

// the bundled module or a newer one (the firmware's: any) is loaded and usable
bool Ready()
{
  const std::string loaded = ReadLine("/sys/module/dvfel/version");
  if (loaded.empty() || access(DVFEL_DEV, R_OK | W_OK) != 0)
    return false;
  // versions are dates (YYYY.MM.DD[.n]): compare as strings; the library
  // checks its own minimum (dvfp_available)
  return FirmwareModule() || loaded >= std::string(BundledVersion());
}

// the app's private directory: /data/data/<package>/files
std::string AppDir()
{
  std::string pkg = ReadLine("/proc/self/cmdline");
  pkg = pkg.c_str(); // up to the first NUL
  const size_t colon = pkg.find(':'); // a process of the app (pkg:name)
  if (colon != std::string::npos)
    pkg.resize(colon);
  if (pkg.empty() || pkg.find('/') != std::string::npos)
    return "";
  const std::string dir = "/data/data/" + pkg + "/files";
  mkdir(dir.c_str(), 0700);
  return access(dir.c_str(), W_OK) == 0 ? dir : "";
}

void SetMsg(char* msg, size_t size, const char* text)
{
  if (msg && size)
    snprintf(msg, size, "%s", text);
}
} // namespace

extern "C" {

int dvfp_module_install(const char* dir, dvfp_ask_fn ask, void* opaque, char* msg, size_t msg_size)
{
  static std::mutex lock;
  static int result = 1; // > 0: not tried in this process
  static bool fw_logged = false;
  std::lock_guard<std::mutex> l(lock);

  SetMsg(msg, msg_size, "");
  char board[PROP_VALUE_MAX] = {};
  __system_property_get("ro.board.platform", board);
  if (!strcmp(board, "gazelle"))
  {
    // The Gazelle experiment is manually loaded and strictly session-only.
    // Never run the Dune boot installer or replace a module on this device.
    if (dvfp_available()) return DVFP_MODULE_READY;
    SetMsg(msg, msg_size, "Gazelle test helper is not loaded or accessible");
    return DVFP_MODULE_FAILED;
  }
  if (Ready())
  {
    if (FirmwareModule() && !fw_logged)
    {
      const std::string loaded = ReadLine("/sys/module/dvfel/version");
      Log(ANDROID_LOG_INFO, "kernel module %s of the firmware (plugin s928x_dvfel)%s",
          loaded.c_str(), loaded < std::string(BundledVersion()) ? ", older than the bundled one" : "");
      fw_logged = true;
    }
    return DVFP_MODULE_READY;
  }
  if (FirmwareModule())
  {
    Log(ANDROID_LOG_WARN, "the firmware's plugin s928x_dvfel provides the kernel module, "
                          "but none is usable: not installed here");
    SetMsg(msg, msg_size, "the kernel module of the firmware (plugin S928X DV FEL) is not loaded");
    return DVFP_MODULE_FAILED;
  }
  size_t koSize = 0;
  const unsigned char* koData = BundledKo(koSize);
  if (!koData || !koSize)
  {
    SetMsg(msg, msg_size, "no kernel module for this player in this build");
    return DVFP_MODULE_FAILED;
  }
  const char* version = BundledVersion();
  if (result <= 0)
  {
    // once per process
    SetMsg(msg, msg_size, result == DVFP_MODULE_DECLINED ? "installation declined"
                                                         : "installation failed (see the log)");
    return result;
  }
  const std::string d = dir && *dir ? std::string(dir) : AppDir();
  if (d.empty())
  {
    SetMsg(msg, msg_size, "no app directory for the installation");
    return result = DVFP_MODULE_FAILED;
  }
  const std::string ko = d + "/dvfel.ko", script = d + "/dvfel_install.sh",
                    declined = d + "/dvfel_root_declined";
  {
    std::ofstream f(ko, std::ios::binary | std::ios::trunc);
    f.write(reinterpret_cast<const char*>(koData), static_cast<std::streamsize>(koSize));
    std::ofstream s(script, std::ios::trunc);
    s << INSTALL_SCRIPT;
    if (!f || !s)
    {
      SetMsg(msg, msg_size, "cannot write the installation files");
      return result = DVFP_MODULE_FAILED;
    }
  }
  if (access("/system/bin/su", X_OK) != 0 && access("/system/xbin/su", X_OK) != 0)
  {
    Log(ANDROID_LOG_WARN, "no su: the kernel module cannot be installed");
    SetMsg(msg, msg_size, "the kernel module cannot be installed: no su on this player");
    return result = DVFP_MODULE_NO_ROOT;
  }

  // su answers only while the firmware's root access is on: ask the user to
  // switch it on for the installation (an app may set the property on this
  // firmware), then restore the previous state
  std::string previous;
  bool switched = false;
  if (!SuWorks())
  {
    if (ReadLine(declined) == version)
    {
      Log(ANDROID_LOG_INFO, "root access off, the module installation was declined earlier");
      SetMsg(msg, msg_size, "installation declined");
      return result = DVFP_MODULE_DECLINED;
    }
    const bool yes =
        ask && ask(opaque, "Dolby Vision FEL",
                   "To play Dolby Vision FEL with its enhancement layer, a kernel module is "
                   "installed once. This needs root access, which is switched off on this "
                   "player.\n\nSwitch root access on for the installation? It is switched off "
                   "again afterwards.") > 0;
    if (!yes)
    {
      std::ofstream(declined, std::ios::trunc) << version << '\n';
      Log(ANDROID_LOG_INFO, "root access off, the module installation declined");
      SetMsg(msg, msg_size, "not installed: FEL plays without the enhancement layer");
      return result = DVFP_MODULE_DECLINED;
    }
    Run(std::string("getprop ") + ROOT_PROP, nullptr, &previous);
    Run(std::string("setprop ") + ROOT_PROP + " 1", "root access");
    switched = true;
    bool ok = false;
    for (int i = 0; i < 20 && !ok; i++)
    {
      std::this_thread::sleep_for(std::chrono::milliseconds(500));
      ok = SuWorks();
    }
    Log(ANDROID_LOG_INFO, "root access switched on (was '%s'): %s", previous.c_str(),
        ok ? "su works" : "su still unavailable");
    if (!ok)
    {
      Run(std::string("setprop ") + ROOT_PROP + " '" + previous + "'", "root access");
      SetMsg(msg, msg_size, "root access could not be switched on");
      return result = DVFP_MODULE_NO_ROOT;
    }
  }

  const int rc = Run("timeout 90 su -c 'sh " + script + " " + ko + "'", "module install");
  if (switched)
  {
    Run(std::string("setprop ") + ROOT_PROP + " '" + previous + "'", "root access");
    Log(ANDROID_LOG_INFO, "root access restored to '%s'", previous.c_str());
  }
  unlink(ko.c_str());
  if (!Ready())
  {
    Log(ANDROID_LOG_WARN, "kernel module installation failed (status %d)", rc);
    SetMsg(msg, msg_size, "kernel module not installed (see the log, tag dvfelplay)");
    return result = DVFP_MODULE_FAILED;
  }
  unlink(declined.c_str());
  Log(ANDROID_LOG_INFO, "kernel module %s installed", version);
  SetMsg(msg, msg_size, "kernel module installed");
  return result = DVFP_MODULE_INSTALLED;
}

const char* dvfp_bundled_module_version(void)
{
  size_t size;
  return BundledKo(size) ? BundledVersion() : "";
}

} // extern "C"
