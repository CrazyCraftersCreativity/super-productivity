/**
 * Renders a habit's Material Symbols icon to a small PNG, so the native widget can
 * show exactly the icons the app shows without bundling the icon font natively
 * (Android cannot load the app's woff2 font file).
 *
 * The glyph is drawn white on a transparent background; the widget tints it.
 */

const FONT_FAMILY = 'Material Symbols Outlined';
const ICON_SIZE_PX = 96;
const PNG_DATA_URL_PREFIX = 'data:image/png;base64,';

const cache = new Map<string, Promise<string | null>>();

const renderIcon = async (iconName: string): Promise<string | null> => {
  // `:name` icons are custom SVGs registered with the Material icon registry, and
  // anything else that is not a plain ligature name cannot be drawn from the font.
  // The widget falls back to the habit's first letter.
  if (!/^[a-z0-9_]+$/.test(iconName)) {
    return null;
  }
  const font = `${ICON_SIZE_PX}px "${FONT_FAMILY}"`;
  await document.fonts.load(font, iconName);

  const canvas = document.createElement('canvas');
  canvas.width = ICON_SIZE_PX;
  canvas.height = ICON_SIZE_PX;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return null;
  }
  ctx.font = font;
  ctx.fillStyle = '#ffffff';
  ctx.textAlign = 'center';
  // 'middle' is the middle of the em square, which is where icon glyphs are centred.
  ctx.textBaseline = 'middle';

  // A name the font has no icon for (or a font that has not loaded) would be drawn
  // as plain letters, far wider than the single one-em icon glyph.
  if (ctx.measureText(iconName).width > ICON_SIZE_PX * 1.25) {
    return null;
  }
  ctx.fillText(iconName, ICON_SIZE_PX / 2, ICON_SIZE_PX / 2);

  const dataUrl = canvas.toDataURL('image/png');
  return dataUrl.startsWith(PNG_DATA_URL_PREFIX)
    ? dataUrl.slice(PNG_DATA_URL_PREFIX.length)
    : null;
};

/**
 * Base64 PNG for an icon name, or null if it cannot be rendered. Successful renders
 * are cached for the session; failures are not, so a font that was still loading is
 * retried on the next push.
 */
export const renderHabitIconBase64 = (iconName: string): Promise<string | null> => {
  const cached = cache.get(iconName);
  if (cached) {
    return cached;
  }
  const pending = renderIcon(iconName)
    .catch(() => null)
    .then((result) => {
      if (result === null && /^[a-z0-9_]+$/.test(iconName)) {
        cache.delete(iconName);
      }
      return result;
    });
  cache.set(iconName, pending);
  return pending;
};
