#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "=========================================="
echo "🚀 Setting up Saara AI Voice Assistant"
echo "=========================================="

# Ensure in home directory and remove broken folder
cd ~
rm -rf saara
mkdir -p saara
cd saara

# Download latest complete project archive
echo "📦 Downloading Saara project files..."
curl -sL https://ais-pre-ah3kexuh4tvfwcobgjdcyw-997804391922.asia-east1.run.app/saara-app.tar.gz -o saara.tar.gz
tar -xzf saara.tar.gz
rm saara.tar.gz

# Setup .env
if [ ! -f .env ]; then
  cat << 'EOF' > .env
GEMINI_API_KEY=
PORT=3000
EOF
fi

echo "📥 Installing npm dependencies (this takes ~30 seconds)..."
npm install

echo ""
echo "=========================================="
echo "✅ Saara is installed successfully!"
echo "=========================================="
echo "Starting local server at http://localhost:3000"
echo "Open Chrome on your phone and go to: http://localhost:3000"
echo "=========================================="

npm run dev
