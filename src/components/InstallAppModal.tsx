import React, { useState, useEffect } from 'react';
import {
  X,
  Smartphone,
  Download,
  Share2,
  Copy,
  Check,
  QrCode,
  Sparkles,
  ExternalLink,
  ShieldCheck,
  CheckCircle2,
  ArrowRight,
  Terminal
} from 'lucide-react';

interface InstallAppModalProps {
  isOpen: boolean;
  onClose: () => void;
  deferredPrompt: any;
  onInstallSuccess?: () => void;
}

export const InstallAppModal: React.FC<InstallAppModalProps> = ({
  isOpen,
  onClose,
  deferredPrompt,
  onInstallSuccess
}) => {
  const [copied, setCopied] = useState(false);
  const [activeOS, setActiveOS] = useState<'android' | 'ios' | 'apk' | 'termux'>('android');
  const [appUrl, setAppUrl] = useState('');

  useEffect(() => {
    if (typeof window !== 'undefined') {
      setAppUrl(window.location.origin);
    }
  }, []);

  if (!isOpen) return null;

  const handleCopyLink = () => {
    navigator.clipboard.writeText(appUrl || window.location.href);
    setCopied(true);
    setTimeout(() => setCopied(false), 2500);
  };

  const handlePromptInstall = async () => {
    if (deferredPrompt) {
      deferredPrompt.prompt();
      const { outcome } = await deferredPrompt.userChoice;
      if (outcome === 'accepted') {
        onInstallSuccess?.();
        onClose();
      }
    }
  };

  // QR code SVG generator for mobile camera scanning
  // Encodes URL into a visual scannable QR representation
  const qrCodeUrl = `https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(
    appUrl || window.location.href
  )}&bgcolor=020617&color=6366f1&margin=2`;

  return (
    <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4 animate-in fade-in zoom-in-95 duration-200">
      <div className="bg-slate-900 border border-indigo-500/50 w-full max-w-lg rounded-3xl shadow-2xl overflow-hidden flex flex-col max-h-[92vh]">
        {/* Header */}
        <div className="px-5 py-4 bg-slate-950/80 border-b border-slate-800 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-indigo-600 to-purple-600 flex items-center justify-center shadow-lg shadow-indigo-600/30">
              <Download className="w-5 h-5 text-white" />
            </div>
            <div>
              <h3 className="font-bold text-sm text-white">Download & Install Saara on Mobile</h3>
              <p className="text-[11px] text-slate-400">Install as standalone app on Android & iOS</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Tab switch */}
        <div className="flex border-b border-slate-800 bg-slate-950/40 px-5 pt-2 shrink-0">
          <button
            onClick={() => setActiveOS('android')}
            className={`pb-2.5 px-3.5 text-xs font-semibold border-b-2 transition flex items-center gap-1.5 ${
              activeOS === 'android'
                ? 'border-emerald-500 text-emerald-400'
                : 'border-transparent text-slate-400 hover:text-slate-300'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5" />
            Android Phone
          </button>
          <button
            onClick={() => setActiveOS('ios')}
            className={`pb-2.5 px-3.5 text-xs font-semibold border-b-2 transition flex items-center gap-1.5 ${
              activeOS === 'ios'
                ? 'border-indigo-500 text-indigo-400'
                : 'border-transparent text-slate-400 hover:text-slate-300'
            }`}
          >
            <span>🍎</span>
            iPhone / iPad
          </button>
          <button
            onClick={() => setActiveOS('apk')}
            className={`pb-2.5 px-3.5 text-xs font-semibold border-b-2 transition flex items-center gap-1.5 ${
              activeOS === 'apk'
                ? 'border-purple-500 text-purple-400'
                : 'border-transparent text-slate-400 hover:text-slate-300'
            }`}
          >
            <Download className="w-3.5 h-3.5" />
            APK / Play Store
          </button>
          <button
            onClick={() => setActiveOS('termux')}
            className={`pb-2.5 px-3.5 text-xs font-semibold border-b-2 transition flex items-center gap-1.5 ${
              activeOS === 'termux'
                ? 'border-amber-500 text-amber-400'
                : 'border-transparent text-slate-400 hover:text-slate-300'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" />
            Termux (Local)
          </button>
        </div>

        {/* Body */}
        <div className="p-5 overflow-y-auto space-y-4 text-xs">
          {/* Quick Instant Install Banner if supported */}
          {deferredPrompt && (
            <div className="p-3.5 bg-gradient-to-r from-indigo-950/80 to-purple-950/80 border border-indigo-500/40 rounded-2xl flex items-center justify-between">
              <div>
                <p className="font-semibold text-white">Browser Ready to Install!</p>
                <p className="text-[11px] text-indigo-200">Tap below to add Saara to your home screen now.</p>
              </div>
              <button
                onClick={handlePromptInstall}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl font-semibold shadow-lg shadow-indigo-600/30 flex items-center gap-1.5"
              >
                <Download className="w-4 h-4" />
                Install Now
              </button>
            </div>
          )}

          {/* QR Code and Link section */}
          <div className="p-4 bg-slate-950/70 border border-slate-800 rounded-2xl flex flex-col sm:flex-row items-center gap-4">
            <div className="p-2 bg-slate-950 rounded-xl border border-slate-800 shadow-md shrink-0">
              <img
                src={qrCodeUrl}
                alt="Scan with phone"
                className="w-28 h-28 object-contain rounded-lg"
              />
            </div>
            <div className="flex-1 space-y-2 text-center sm:text-left">
              <p className="font-semibold text-slate-200 text-xs flex items-center justify-center sm:justify-start gap-1.5">
                <QrCode className="w-4 h-4 text-indigo-400" />
                Scan with your phone's camera
              </p>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Point your mobile phone camera or QR scanner at this code to open Saara instantly on your mobile browser.
              </p>
              <div className="flex items-center gap-2 pt-1">
                <input
                  type="text"
                  readOnly
                  value={appUrl || window.location.href}
                  className="bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-1 text-[11px] text-slate-300 font-mono flex-1 truncate"
                />
                <button
                  onClick={handleCopyLink}
                  className="px-3 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg text-xs font-medium flex items-center gap-1 transition shrink-0"
                >
                  {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  {copied ? 'Copied!' : 'Copy'}
                </button>
              </div>
            </div>
          </div>

          {/* Tab 1: Android Guide */}
          {activeOS === 'android' && (
            <div className="space-y-3">
              <h4 className="font-semibold text-emerald-400 flex items-center gap-1.5">
                <Smartphone className="w-4 h-4" />
                How to Install on Android (Chrome / Edge / Samsung Internet):
              </h4>
              <ol className="space-y-2.5 text-slate-300 text-[11px]">
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-emerald-600/20 text-emerald-400 font-bold flex items-center justify-center shrink-0">
                    1
                  </span>
                  <span>
                    Open the link on <strong>Google Chrome</strong> or <strong>Samsung Internet</strong> on your phone.
                  </span>
                </li>
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-emerald-600/20 text-emerald-400 font-bold flex items-center justify-center shrink-0">
                    2
                  </span>
                  <span>
                    Look for the <strong>"Install App"</strong> prompt, or tap the three dots menu (<strong>⋮</strong>) in the top-right corner of Chrome.
                  </span>
                </li>
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-emerald-600/20 text-emerald-400 font-bold flex items-center justify-center shrink-0">
                    3
                  </span>
                  <span>
                    Select <strong>"Install app"</strong> or <strong>"Add to Home screen"</strong>.
                  </span>
                </li>
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-emerald-600/20 text-emerald-400 font-bold flex items-center justify-center shrink-0">
                    4
                  </span>
                  <span>
                    Confirm by tapping <strong>Install</strong>. Saara will appear on your phone's home screen and app drawer as a full native app!
                  </span>
                </li>
              </ol>
            </div>
          )}

          {/* Tab 2: iPhone Guide */}
          {activeOS === 'ios' && (
            <div className="space-y-3">
              <h4 className="font-semibold text-indigo-400 flex items-center gap-1.5">
                <span>🍎</span>
                How to Install on iPhone / iPad (Safari):
              </h4>
              <ol className="space-y-2.5 text-slate-300 text-[11px]">
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-indigo-600/20 text-indigo-400 font-bold flex items-center justify-center shrink-0">
                    1
                  </span>
                  <span>
                    Open the link using <strong>Safari</strong> on your iPhone.
                  </span>
                </li>
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-indigo-600/20 text-indigo-400 font-bold flex items-center justify-center shrink-0">
                    2
                  </span>
                  <span>
                    Tap the <strong>Share</strong> button at the bottom of the screen (the box with an upward-pointing arrow).
                  </span>
                </li>
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-indigo-600/20 text-indigo-400 font-bold flex items-center justify-center shrink-0">
                    3
                  </span>
                  <span>
                    Scroll down and tap <strong>"Add to Home Screen"</strong>.
                  </span>
                </li>
                <li className="flex items-start gap-2.5 p-2.5 bg-slate-950/40 rounded-xl border border-slate-800/80">
                  <span className="w-5 h-5 rounded-full bg-indigo-600/20 text-indigo-400 font-bold flex items-center justify-center shrink-0">
                    4
                  </span>
                  <span>
                    Tap <strong>"Add"</strong> in the top-right corner. Saara launches in full-screen standalone mode with no browser URL bar!
                  </span>
                </li>
              </ol>
            </div>
          )}

          {/* Tab 3: Native APK Package */}
          {activeOS === 'apk' && (
            <div className="space-y-3">
              <h4 className="font-semibold text-purple-400 flex items-center gap-1.5">
                <Download className="w-4 h-4" />
                Generating Standalone Android APK (.apk / .aab)
              </h4>
              <div className="p-3 bg-slate-950/60 rounded-xl border border-slate-800 space-y-2 text-[11px] text-slate-300 leading-relaxed">
                <p>
                  Because Saara is built with full PWA compliance, you can turn it into an Android <strong>APK</strong> or Google Play Store package in under 2 minutes:
                </p>
                <div className="p-2.5 rounded-lg bg-slate-900 border border-slate-800 font-mono text-[10px] text-purple-300">
                  1. Visit <strong>pwabuilder.com</strong> on your computer.<br />
                  2. Paste this app's URL: <span className="text-white">{appUrl || window.location.origin}</span><br />
                  3. Click "Package for Android" → Download your signed <strong>.apk</strong> or Google Play Store package!
                </div>
                <p className="text-[10px] text-slate-500">
                  Once downloaded, you can install the APK directly on any Android device by enabling "Install from unknown sources" or publish it to the Google Play Console.
                </p>
              </div>
            </div>
          )}

          {/* Tab 4: Termux 1-Command Setup */}
          {activeOS === 'termux' && (
            <div className="space-y-3">
              <h4 className="font-semibold text-amber-400 flex items-center gap-1.5">
                <Terminal className="w-4 h-4" />
                Fix Termux Setup in 1 Command
              </h4>
              <p className="text-[11px] text-slate-300">
                Run this single command in Termux to download the complete working project, fix the missing <code>package.json</code> error, and launch Saara:
              </p>
              <div className="p-3 bg-slate-950 rounded-xl border border-amber-500/30 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-[10px] font-mono text-amber-400 font-semibold">Copy & Paste in Termux:</span>
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText('curl -sL https://ais-pre-ah3kexuh4tvfwcobgjdcyw-997804391922.asia-east1.run.app/setup.sh | bash');
                      setCopied(true);
                      setTimeout(() => setCopied(false), 2000);
                    }}
                    className="px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 text-[10px] font-medium hover:bg-amber-500 hover:text-black transition flex items-center gap-1"
                  >
                    {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />}
                    {copied ? 'Copied!' : 'Copy Command'}
                  </button>
                </div>
                <pre className="p-2 bg-slate-900 rounded font-mono text-[10px] text-amber-200 overflow-x-auto whitespace-pre-wrap select-all">
curl -sL https://ais-pre-ah3kexuh4tvfwcobgjdcyw-997804391922.asia-east1.run.app/setup.sh | bash
                </pre>
              </div>
              <div className="text-[10px] text-slate-400 space-y-1">
                <p><strong>What this script does automatically:</strong></p>
                <ul className="list-disc list-inside space-y-0.5 text-slate-400">
                  <li>Removes the previous empty/broken <code>~/saara</code> folder</li>
                  <li>Downloads the complete codebase directly from this server</li>
                  <li>Installs dependencies via <code>npm install</code></li>
                  <li>Launches the local dev server at <code>http://localhost:3000</code></li>
                </ul>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 bg-slate-950 border-t border-slate-800 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-1.5 text-[11px] text-emerald-400 font-medium">
            <ShieldCheck className="w-4 h-4" />
            Full Screen Standalone Mode Supported
          </div>
          <button
            onClick={onClose}
            className="px-4 py-1.5 bg-slate-800 hover:bg-slate-700 text-white rounded-xl text-xs font-medium"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
