import React, { useEffect, useRef, useState } from 'react';
import { Camera, Monitor, X, SwitchCamera, ShieldCheck, Download, AlertTriangle } from 'lucide-react';

interface CameraScreenModalProps {
  mode: 'camera' | 'screen' | null;
  onClose: () => void;
  onSnapshot?: (dataUrl: string) => void;
}

export const CameraScreenModal: React.FC<CameraScreenModalProps> = ({ mode, onClose, onSnapshot }) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [facingMode, setFacingMode] = useState<'user' | 'environment'>('user');
  const [error, setError] = useState<string | null>(null);
  const [snapshotUrl, setSnapshotUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!mode) return;
    startMedia();

    return () => {
      stopMedia();
    };
  }, [mode, facingMode]);

  const stopMedia = () => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(track => track.stop());
      streamRef.current = null;
    }
  };

  const startMedia = async () => {
    stopMedia();
    setError(null);
    try {
      let stream: MediaStream;
      if (mode === 'camera') {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode },
          audio: false
        });
      } else {
        stream = await navigator.mediaDevices.getDisplayMedia({
          video: true,
          audio: false
        });
      }

      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
      }
    } catch (err: any) {
      setError(err?.message || 'Permission denied or device unavailable.');
    }
  };

  const takeSnapshot = () => {
    if (!videoRef.current) return;
    const canvas = document.createElement('canvas');
    canvas.width = videoRef.current.videoWidth || 640;
    canvas.height = videoRef.current.videoHeight || 480;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.drawImage(videoRef.current, 0, 0, canvas.width, canvas.height);
    const dataUrl = canvas.toDataURL('image/jpeg', 0.85);
    setSnapshotUrl(dataUrl);
    if (onSnapshot) {
      onSnapshot(dataUrl);
    }
  };

  if (!mode) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-700 w-full max-w-2xl rounded-2xl shadow-2xl overflow-hidden flex flex-col">
        {/* Header */}
        <div className="px-5 py-3.5 border-b border-slate-800 flex items-center justify-between bg-slate-950/60">
          <div className="flex items-center gap-2.5">
            {mode === 'camera' ? (
              <Camera className="w-5 h-5 text-indigo-400" />
            ) : (
              <Monitor className="w-5 h-5 text-sky-400" />
            )}
            <div>
              <h3 className="font-semibold text-slate-100 text-sm">
                {mode === 'camera' ? 'Live Camera Feed' : 'Live Screen Share'}
              </h3>
              <p className="text-xs text-emerald-400 flex items-center gap-1 font-medium">
                <ShieldCheck className="w-3.5 h-3.5" />
                Strict Privacy: Local preview only — frames are never uploaded
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Video feed container */}
        <div className="relative bg-black aspect-video flex items-center justify-center overflow-hidden">
          {error ? (
            <div className="p-6 text-center max-w-md">
              <AlertTriangle className="w-10 h-10 text-amber-400 mx-auto mb-2" />
              <p className="text-sm font-medium text-slate-200">Unable to access {mode}</p>
              <p className="text-xs text-slate-400 mt-1">{error}</p>
              <button
                onClick={startMedia}
                className="mt-4 px-4 py-1.5 text-xs font-medium bg-indigo-600 hover:bg-indigo-500 rounded-lg text-white"
              >
                Retry Permission
              </button>
            </div>
          ) : (
            <video
              ref={videoRef}
              autoPlay
              playsInline
              muted
              className="w-full h-full object-contain"
            />
          )}

          {/* Overlay watermarks */}
          <div className="absolute top-3 left-3 bg-black/60 backdrop-blur-md px-2.5 py-1 rounded-md text-[11px] font-mono text-emerald-300 flex items-center gap-1.5 border border-emerald-500/30">
            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
            DEVICE-LOCAL STREAM
          </div>

          {snapshotUrl && (
            <div className="absolute bottom-3 right-3 p-1.5 bg-slate-900/90 border border-slate-700 rounded-lg shadow-xl">
              <p className="text-[10px] text-slate-300 mb-1">Last Shutter:</p>
              <img src={snapshotUrl} alt="Snapshot" className="w-24 h-16 object-cover rounded" />
            </div>
          )}
        </div>

        {/* Action Controls */}
        <div className="p-4 bg-slate-950 flex items-center justify-between gap-3 border-t border-slate-800">
          <div className="flex items-center gap-2">
            {mode === 'camera' && (
              <button
                onClick={() => setFacingMode(prev => (prev === 'user' ? 'environment' : 'user'))}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg transition"
              >
                <SwitchCamera className="w-4 h-4" />
                Flip ({facingMode === 'user' ? 'Front' : 'Back'})
              </button>
            )}
            <button
              onClick={takeSnapshot}
              disabled={!!error}
              className="flex items-center gap-1.5 px-3.5 py-1.5 text-xs font-medium bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg transition disabled:opacity-50"
            >
              <Camera className="w-4 h-4" />
              Take Snapshot
            </button>
          </div>

          <button
            onClick={onClose}
            className="px-4 py-1.5 text-xs font-medium text-slate-400 hover:text-slate-200"
          >
            Close Feed
          </button>
        </div>
      </div>
    </div>
  );
};
