# Saturn's runtime environment, for the debug test app (io.github.pimobile.spike), run as the app user.
# Used by test-extensions.sh: sh phone-env.sh '<bash command>'. PIMOBILE_BROWSER_URL is a dummy so shims start.
D=/data/user/0/io.github.pimobile.spike; F=$D/files; U=$F/usr
ND=$(dirname $(readlink $U/bin/node))
export PATH=$U/bin:/system/bin LD_LIBRARY_PATH=$ND HOME=$F/home TMPDIR=$D/cache/tmp PREFIX=$U SHELL=$U/bin/bash LANG=en_US.UTF-8 TERM=xterm-256color
export OPENSSL_CONF=$U/etc/openssl.cnf SSL_CERT_FILE=$U/etc/tls/cert.pem CURL_CA_BUNDLE=$U/etc/tls/cert.pem GIT_SSL_CAINFO=$U/etc/tls/cert.pem
export NODE_OPTIONS="--use-bundled-ca --require $U/lib/saturn-compat.cjs" NPM_CONFIG_NODE_GYP=$U/bin/node-gyp SATURN_NPM_CLI=$F/pi/node_modules/npm/bin/npm-cli.js PIMOBILE_BROWSER_URL=http://127.0.0.1:1 GIT_EXEC_PATH=$U/libexec/git-core BASH_ENV=$U/etc/pi-bash-env.sh PI_SKIP_VERSION_CHECK=1
export NPM_CONFIG_UPDATE_NOTIFIER=false NPM_CONFIG_FUND=false TERMUX_VERSION=saturn LD_PRELOAD=$ND/libpiexec.so
mkdir -p $TMPDIR; cd $HOME
exec $U/bin/bash -c "$1"
