#!/system/bin/sh
[ "$ARCH" = arm64 ] || abort "! arm64 only"
set_perm "$MODPATH/thor-mouse" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
