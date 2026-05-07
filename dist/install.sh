#!/bin/bash
set -e

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="$HOME/.any2tidb"
JAR_FILE="any2tidb-1.0.0.jar"
VERSION="1.0.0"

echo "==> Installing any2tidb ${VERSION}..."

# 1. check java
printf "==> Checking Java... "
if ! command -v java &>/dev/null; then
    echo
    echo "ERROR: java not found. Please install Java 17+."
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1)
echo "ok ($JAVA_VER)"

# 2. install jar
echo "==> Installing jar..."
mkdir -p "$INSTALL_DIR"
cp "$SELF_DIR/$JAR_FILE" "$INSTALL_DIR/"

# 3. create wrapper
echo "==> Installing wrapper..."
WRAPPER=""
NEED_PATH_HINT=false
for dir in "$HOME/.local/bin" "$HOME/bin" "/usr/local/bin"; do
    if [[ ":$PATH:" == *":$dir:"* ]] && mkdir -p "$dir" 2>/dev/null; then
        WRAPPER="$dir/any2tidb"
        break
    fi
done
if [[ -z "$WRAPPER" ]]; then
    WRAPPER="$HOME/.local/bin/any2tidb"
    mkdir -p "$HOME/.local/bin"
    NEED_PATH_HINT=true
fi

cat > "$WRAPPER" << WRAPPER_EOF
#!/bin/bash
exec java -jar "\$HOME/.any2tidb/$JAR_FILE" "\$@"
WRAPPER_EOF
chmod +x "$WRAPPER"

# 4. create short alias — a2t
A2T_LINK="$(dirname "$WRAPPER")/a2t"
ln -sf "$(basename "$WRAPPER")" "$A2T_LINK"

echo
echo "🍺  any2tidb ${VERSION} was successfully installed!"
echo
echo "    jar   ${INSTALL_DIR}/${JAR_FILE}"
echo "    bin   ${WRAPPER}"
echo "    bin   ${A2T_LINK}"
echo
if $NEED_PATH_HINT; then
    echo "==> Caveats"
    echo "Add ~/.local/bin to your PATH if it's not already there."
    echo
fi
printf "==> Try: \033[1ma2t --help\033[0m  (or \033[1many2tidb --help\033[0m)\n"
