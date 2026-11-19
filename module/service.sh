#!/system/bin/sh
MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 5

# Run as shell: uinput and /dev/input are reachable without root, so keep root out of the input path.
# The state file lives in /data/local/tmp so the app's Shizuku shell can signal it.
exec su 2000 -c "setsid $MODDIR/thor-mouse </dev/null >/data/local/tmp/thor-emu-mouse.log 2>&1 &"
