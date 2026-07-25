import { registerPlugin } from '@capacitor/core';

export interface QueueItem {
  id: string;
  title: string;
  artist: string;
  iconUri?: string;
}

export interface AudioFocusPlugin {
  requestFocus(): Promise<{ granted: boolean }>;
  requestFocusTransient(): Promise<{ granted: boolean }>;
  abandonFocus(): Promise<void>;
  updateMetadata(options: {
    title: string;
    artist: string;
    album: string;
    coverUrl: string;
  }): Promise<void>;
  updatePlaybackState(options: {
    playing: boolean;
    position: number;
    duration: number;
  }): Promise<void>;
  updateQueue(options: { items: QueueItem[] }): Promise<void>;
  isActive(): Promise<{ hasFocus: boolean; isPlaying: boolean }>;
  pause(): Promise<void>;
  addListener(
    eventName: 'audioFocusGained',
    listener: () => void
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: 'audioFocusLost',
    listener: (data: { transient: boolean }) => void
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: 'mediaButton',
    listener: (data: { action: string }) => void
  ): Promise<{ remove: () => void }>;
}

export const AudioFocus = registerPlugin<AudioFocusPlugin>('AudioFocus');
