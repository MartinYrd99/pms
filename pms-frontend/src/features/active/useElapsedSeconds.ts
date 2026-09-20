import { useEffect, useState } from "react";

/**
 * Whole seconds elapsed since `startedAt`, ticking every second entirely in the browser's clock —
 * the server is only ever asked for the session list, never polled for the running time.
 */
export function useElapsedSeconds(startedAt: string): number {
  const startedAtMs = new Date(startedAt).getTime();
  const [elapsedMs, setElapsedMs] = useState(() => Date.now() - startedAtMs);

  useEffect(() => {
    const intervalId = setInterval(() => {
      setElapsedMs(Date.now() - startedAtMs);
    }, 1000);

    return () => clearInterval(intervalId);
  }, [startedAtMs]);

  return Math.max(0, Math.floor(elapsedMs / 1000));
}

/** Formats whole seconds as zero-padded HH:MM:SS. */
export function formatElapsedDuration(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (value: number) => value.toString().padStart(2, "0");

  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
}