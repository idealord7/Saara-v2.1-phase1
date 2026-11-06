import React, { useEffect, useRef } from 'react';

interface AudioWaveformProps {
  isActive: boolean;
  isSpeaking: boolean;
  statusText: string;
}

export const AudioWaveform: React.FC<AudioWaveformProps> = ({ isActive, isSpeaking, statusText }) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animationId: number;
    let phase = 0;

    const render = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      const width = canvas.width;
      const height = canvas.height;
      const centerY = height / 2;

      const numBars = 32;
      const barWidth = 3;
      const gap = (width - numBars * barWidth) / (numBars - 1);

      for (let i = 0; i < numBars; i++) {
        const x = i * (barWidth + gap);
        let barHeight = 4;

        if (isActive || isSpeaking) {
          // Dynamic oscillation
          const speed = isSpeaking ? 0.08 : 0.05;
          const amplitude = isSpeaking ? 22 : 14;
          const wave = Math.sin(phase + i * 0.3) * Math.cos(phase * 0.7 + i * 0.2);
          barHeight = Math.max(4, Math.abs(wave) * amplitude + 4);
        }

        const gradient = ctx.createLinearGradient(0, centerY - barHeight, 0, centerY + barHeight);
        if (isSpeaking) {
          gradient.addColorStop(0, '#ec4899'); // Pink
          gradient.addColorStop(1, '#a855f7'); // Purple
        } else if (isActive) {
          gradient.addColorStop(0, '#38bdf8'); // Sky blue
          gradient.addColorStop(1, '#6366f1'); // Indigo
        } else {
          gradient.addColorStop(0, '#475569'); // Slate
          gradient.addColorStop(1, '#334155');
        }

        ctx.fillStyle = gradient;
        ctx.beginPath();
        ctx.roundRect(x, centerY - barHeight / 2, barWidth, barHeight, 2);
        ctx.fill();
      }

      phase += 0.06;
      animationId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animationId);
    };
  }, [isActive, isSpeaking]);

  return (
    <div className="flex flex-col items-center justify-center p-4">
      <canvas
        ref={canvasRef}
        width={240}
        height={50}
        className="w-60 h-12 rounded-lg"
      />
      <div className="mt-2 text-xs font-medium tracking-wide flex items-center gap-1.5">
        <span
          className={`w-2 h-2 rounded-full ${
            isSpeaking
              ? 'bg-purple-400 animate-ping'
              : isActive
              ? 'bg-emerald-400 animate-pulse'
              : 'bg-slate-500'
          }`}
        />
        <span className={isSpeaking ? 'text-purple-300' : isActive ? 'text-emerald-300' : 'text-slate-400'}>
          {statusText}
        </span>
      </div>
    </div>
  );
};
