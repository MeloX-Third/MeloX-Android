import type { HostContext, MusicTrack } from './types.js';

export type UiSlot = 'extensions' | 'pluginDetails' | 'services' | 'search' | 'home' | 'explore' | 'library' | 'songMenu' | 'playerMenu' | 'playlist' | 'album' | 'artist' | 'lyrics' | 'queue' | 'navigation' | 'playerPage';
export interface UiEntry { id: string; title: string; slot: UiSlot }
export interface UiRequest {
  entry: string;
  page: string;
  index: number;
  query: string;
  scope?: { type: 'track' | 'playlist' | 'album' | 'artist'; id: string };
}
export interface UiActionRequest extends UiRequest {
  control: string;
  value?: string | boolean | number | string[];
}
export interface UiActionResult { message?: string; refresh?: boolean }
interface ControlBase { id: string; label: string; description?: string; disabled?: boolean }
interface Option { id: string; label: string }
export type UiControl = ControlBase & (
  | { type: 'toggle'; value: boolean }
  | { type: 'singleChoice'; options: Option[]; value: string }
  | { type: 'multiChoice'; options: Option[]; value: string[] }
  | { type: 'slider'; min: number; max: number; value: number }
  | { type: 'number'; value: number }
  | { type: 'text' | 'url' | 'status' | 'qr'; value: string }
  | { type: 'secret' | 'button' }
  | { type: 'link'; target: string }
);
export interface UiPage {
  title: string;
  template: 'settings' | 'connection' | 'collection' | 'detail' | 'search' | 'status';
  controls: UiControl[];
  tracks?: MusicTrack[];
  hasMore?: boolean;
}
export interface PluginUiMethods {
  uiPage?(request: UiRequest, context: HostContext): Promise<UiPage>;
  uiAction?(request: UiActionRequest, context: HostContext): Promise<UiActionResult>;
}
