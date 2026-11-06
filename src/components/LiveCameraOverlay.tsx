import React, { useRef, useEffect, useState } from 'react';
import { Camera, SwitchCamera, X, Minimize2, Maximize2, ShieldCheck, CameraOff } from 'lucide-react';

interface LiveCameraOverlayProps {
  isOpen: boolean;
  onClose: () => void;
  onSnapshot?: (dataUrl: string) => void;
}

export const LiveCameraOverlay: React.FC<LiveCameraOverlayProps> = ({
  isOpen,
  onClose,
  onSnapshot
}) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [facingMode, setFacingMode] = useState<'user' | 'environment'>('user');
  const [isMinimized, setIsMinimized] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) {
      stopCamera();
      return;
    }
    startCamera();

    return () => {
      stopCamera();
    };
  }, [isOpen, facingMode]);

  const stopCamera = () => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(t => t.stop());
      streamRef.current = null;
    }
  };

  const startCamera = async () => {
    stopCamera();
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode },
        audio: false
      });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
      }
    } catch (err: any) {
      setError(err?.message || 'Camera permission denied or camera not found');
    }
  };

  const handleFlip = () => {
    setFacingMode(prev => (prev === 'user' ? 'environment' : 'user'));
  };

  const handleSnapshot = () => {
    if (!videoRef.current) return;
    const canvas = document.createElement('canvas');
    canvas.width = videoRef.current.videoWidth || 640;
    canvas.height = videoRef.current.videoHeight || 480;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.drawImage(videoRef.current, 0, 0, canvas.width, canvas.height);
    const dataUrl = canvas.toDataURL('image/jpeg', 0.85);
    onSnapshot?.(dataUrl);
  };

  if (!isOpen) return null;

  return (
    <div
      className={`fixed bottom-24 right-6 z-50 bg-slate-900 border-2 border-indigo-500/80 rounded-2xl shadow-2xl overflow-hidden transition-all duration-300 select-none ${
        isMinimized ? 'w-56 h-28' : 'w-72 sm:w-80 h-56 sm:h-64'
      }`}
    >
      {/* Header bar */}
      <div className="bg-slate-950/90 px-3 py-1.5 border-b border-slate-800 flex items-center justify-between">
        <div className="flex items-center gap-1.5 overflow-hidden">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse shrink-0" />
          <span className="text-[11px] font-semibold text-slate-200 truncate">
            {facingMode === 'user' ? 'Front Camera' : 'Back Camera'}
          </span>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={handleFlip}
            className="p-1 rounded text-slate-400 hover:text-white hover:bg-slate-800 transition"
            title="Switch Front/Back Camera"
          >
            <SwitchCamera className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={() => setIsMinimized(prev => !prev)}
            className="p-1 rounded text-slate-400 hover:text-white hover:bg-slate-800 transition"
            title={isMinimized ? 'Expand' : 'Minimize'}
          >
            {isMinimized ? <Maximize2 className="w-3.5 h-3.5" /> : <Minimize2 className="w-3.5 h-3.5" />}
          </button>
          <button
            onClick={onClose}
            className="p-1 rounded text-slate-400 hover:text-rose-400 hover:bg-slate-800 transition"
            title="Stop Camera Sharing"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Video stream container */}
      <div className="relative w-full h-[calc(100%-2.25rem)] bg-black flex items-center justify-center overflow-hidden">
        {error ? (
          <div className="p-3 text-center text-rose-300 text-xs flex flex-col items-center gap-1">
            <CameraOff className="w-6 h-6 text-rose-400" />
            <p className="font-medium text-[11px]">{error}</p>
            <button
              onClick={startCamera}
              className="mt-1 px-2.5 py-1 bg-indigo-600 rounded text-[10px] text-white"
            >
              Retry
            </button>
          </div>
        ) : (
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            className={`w-full h-full object-cover ${facingMode === 'user' ? 'scale-x-[-1]' : ''}`}
          />
        )}

        {/* Floating badge */}
        <div className="absolute top-2 left-2 px-2 py-0.5 rounded bg-black/60 backdrop-blur-md border border-white/10 text-[9px] font-mono text-emerald-400 flex items-center gap-1">
          <ShieldCheck className="w-3 h-3" />
          Sharing with Saara
        </div>

        {/* Shutter Snapshot Button */}
        {!error && !isMinimized && (
          <button
            onClick={handleSnapshot}
            className="absolute bottom-2 right-2 p-2 rounded-full bg-indigo-600/90 hover:bg-indigo-500 text-white shadow-lg backdrop-blur-sm transition"
            title="Snap Frame for Assistant"
          >
            <Camera className="w-4 h-4" />
          </button>
        )}
      </div>
    </div>
  );
};
