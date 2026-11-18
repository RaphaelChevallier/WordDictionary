import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const root = fileURLToPath(new URL("../", import.meta.url));
const output = join(root, "apps", "ios", "MaxFluency", "Resources");
const overrides = {
  en: {
    error_title: "Something went wrong", ok: "OK",
    error_online_cache: "The word was found, but it could not be saved for offline lookup.",
    reminder_note: "The word and its definition, at your local time. Up to 60 days are queued offline and renewed on app use or permitted background refresh. iOS controls delivery.",
    reminder_permission_needed: "Allow MaxFluency notifications in iOS Settings to receive daily reminders.",
    reminder_system_settings: "Open iOS Settings",
    privacy_note: "No account or ads. Saved words and progress stay on your device. An explicit online lookup sends the word to Wiktionary; optional full dictionaries come from reader.dict. Apple translation runs on the device.",
    translation_credit: "Optional translations use Apple's on-device Translation framework. Language downloads require your confirmation; machine translations can be inaccurate. Your original meanings and examples remain visible.",
    translation_provider: "Translated on this device with Apple",
    translation_management: "Apple manages shared translation languages. Manage downloaded languages in Apple's Translate app; removing this app does not remove system language packs.",
    translation_download_message: "Apple needs offline languages for this pair. Continue on Wi-Fi to let iOS ask for the required downloads. Your original meanings stay visible.",
    anki_export_description: "Original words, meanings, examples and source credits. Save an offline .apkg file for any Anki client, or hand one note to installed AnkiMobile. Review history and machine translations are not exported.",
    anki_send_direct: "Send to AnkiMobile",
    anki_not_installed: "AnkiMobile is a separate app, sold separately. Free .apkg export works without it.",
    anki_mobile_setup: "For direct sending, first import a MaxFluency .apkg once to create the MaxFluency deck and note type. Keep those names. AnkiMobile checks duplicates and handles the note confirmation.",
    anki_mobile_opened: "Opened AnkiMobile. Confirm the note there.",
    anki_profile: "Anki profile (optional)",
    anki_export_failed: "Export could not be completed. Your saved words and review progress are unchanged.",
    dictionary_installed: "%1$@ words and variants available offline",
    pack_info: "%1$@ · %2$@ words · %3$@ MB packed",
    tts_no_voice: "No offline voice is installed for %1$@. Download a voice in iOS Accessibility settings.",
    translation_wifi: "Connect to Wi-Fi before downloading offline translation languages.",
    keep_dictionary_open: "Keep MaxFluency open while downloading and preparing search. If interrupted, retry; the existing dictionary stays installed.",
    background_refresh_note: "If iOS does not grant background refresh, open MaxFluency at least once every 60 days to renew future reminders."
  },
  fr: {
    error_title: "Un problème est survenu", ok: "OK",
    error_online_cache: "Le mot a été trouvé, mais son enregistrement pour une consultation hors ligne a échoué.",
    reminder_note: "Le mot et sa définition, à l’heure locale. Jusqu’à 60 jours sont programmés hors ligne, puis renouvelés à l’ouverture ou en arrière-plan si iOS l’autorise. iOS contrôle la distribution.",
    reminder_permission_needed: "Autorisez les notifications de MaxFluency dans les réglages iOS pour recevoir les rappels.",
    reminder_system_settings: "Ouvrir les réglages iOS",
    privacy_note: "Sans compte ni publicité. Vos mots et progrès restent sur votre appareil. Une recherche en ligne explicite envoie le mot au Wiktionnaire ; les dictionnaires complets facultatifs proviennent de reader.dict. La traduction Apple s’effectue sur l’appareil.",
    translation_credit: "Les traductions facultatives utilisent le framework Traduction d’Apple sur l’appareil. Les téléchargements nécessitent votre confirmation ; les traductions automatiques peuvent être inexactes. Les sens et exemples originaux restent visibles.",
    translation_provider: "Traduit sur cet appareil avec Apple",
    translation_management: "Apple gère les langues de traduction partagées. Gérez les téléchargements dans l’app Traduire d’Apple ; supprimer cette app ne supprime pas les langues du système.",
    translation_download_message: "Apple a besoin des langues hors ligne pour cette paire. Continuez en Wi-Fi pour autoriser les téléchargements demandés par iOS. Les sens originaux restent visibles.",
    anki_export_description: "Mots, sens, exemples et sources d’origine. Enregistrez un fichier .apkg hors ligne pour un client Anki, ou transmettez une note à AnkiMobile installé. L’historique de révision et les traductions automatiques ne sont pas exportés.",
    anki_send_direct: "Envoyer à AnkiMobile",
    anki_not_installed: "AnkiMobile est une app distincte, vendue séparément. L’export gratuit .apkg fonctionne sans elle.",
    anki_mobile_setup: "Pour l’envoi direct, importez d’abord un .apkg MaxFluency afin de créer son paquet et son type de note. Conservez leurs noms. AnkiMobile vérifie les doublons et confirme la note.",
    anki_mobile_opened: "AnkiMobile est ouvert. Confirmez-y la note.",
    anki_profile: "Profil Anki (facultatif)",
    anki_export_failed: "L’export a échoué. Vos mots enregistrés et vos révisions restent inchangés.",
    dictionary_installed: "%1$@ mots et variantes disponibles hors ligne",
    pack_info: "%1$@ · %2$@ mots · %3$@ Mo compressés",
    tts_no_voice: "Aucune voix hors ligne pour %1$@. Téléchargez une voix dans les réglages d’accessibilité iOS.",
    translation_wifi: "Connectez-vous au Wi-Fi pour télécharger les langues de traduction hors ligne.",
    keep_dictionary_open: "Gardez MaxFluency ouvert pendant le téléchargement et la préparation. En cas d’interruption, réessayez ; le dictionnaire existant est conservé.",
    background_refresh_note: "Sans actualisation en arrière-plan autorisée par iOS, ouvrez MaxFluency au moins tous les 60 jours pour renouveler les rappels."
  },
  es: {
    error_title: "Ha ocurrido un problema", ok: "OK",
    error_online_cache: "Se encontró la palabra, pero no se pudo guardar para consultarla sin conexión.",
    reminder_note: "La palabra y su definición, a la hora local. Se programan hasta 60 días sin conexión y se renuevan al abrir la app o cuando iOS permite actualizar en segundo plano. iOS controla la entrega.",
    reminder_permission_needed: "Permite las notificaciones de MaxFluency en los ajustes de iOS para recibir recordatorios.",
    reminder_system_settings: "Abrir ajustes de iOS",
    privacy_note: "Sin cuenta ni anuncios. Tus palabras y tu progreso permanecen en el dispositivo. Una consulta explícita envía la palabra a Wikcionario; los diccionarios completos opcionales proceden de reader.dict. La traducción de Apple funciona en el dispositivo.",
    translation_credit: "Las traducciones opcionales usan el framework Traducción de Apple en el dispositivo. Las descargas requieren tu confirmación y las traducciones automáticas pueden ser inexactas. Los significados y ejemplos originales siguen visibles.",
    translation_provider: "Traducido en este dispositivo con Apple",
    translation_management: "Apple gestiona los idiomas de traducción compartidos. Gestiona las descargas en la app Traducir de Apple; eliminar esta app no elimina los idiomas del sistema.",
    translation_download_message: "Apple necesita idiomas sin conexión para este par. Continúa por Wi-Fi para autorizar las descargas que solicite iOS. Los significados originales siguen visibles.",
    anki_export_description: "Palabras, significados, ejemplos y fuentes originales. Guarda un .apkg sin conexión para cualquier cliente Anki, o envía una nota a AnkiMobile instalado. No se exportan el historial de repaso ni las traducciones automáticas.",
    anki_send_direct: "Enviar a AnkiMobile",
    anki_not_installed: "AnkiMobile es una app independiente que se vende por separado. La exportación gratuita .apkg funciona sin ella.",
    anki_mobile_setup: "Para el envío directo, importa primero un .apkg MaxFluency para crear su mazo y tipo de nota. Conserva sus nombres. AnkiMobile comprueba los duplicados y confirma la nota.",
    anki_mobile_opened: "AnkiMobile está abierto. Confirma la nota allí.",
    anki_profile: "Perfil de Anki (opcional)",
    anki_export_failed: "No se pudo completar la exportación. Tus palabras guardadas y tus repasos no han cambiado.",
    dictionary_installed: "%1$@ palabras y variantes disponibles sin conexión",
    pack_info: "%1$@ · %2$@ palabras · %3$@ MB comprimidos",
    tts_no_voice: "No hay una voz sin conexión para %1$@. Descarga una voz en los ajustes de accesibilidad de iOS.",
    translation_wifi: "Conéctate a Wi-Fi para descargar idiomas de traducción sin conexión.",
    keep_dictionary_open: "Mantén MaxFluency abierto durante la descarga y preparación. Si se interrumpe, reinténtalo; el diccionario existente se conserva.",
    background_refresh_note: "Si iOS no permite actualizar en segundo plano, abre MaxFluency al menos cada 60 días para renovar los recordatorios."
  }
};

function xml(value) {
  return value.replace(/&(?:#x([0-9a-f]+)|#(\d+)|(amp|quot|apos|lt|gt));/gi, (_, hex, decimal, name) =>
    hex ? String.fromCodePoint(parseInt(hex, 16)) : decimal ? String.fromCodePoint(Number(decimal)) :
      ({ amp: "&", quot: '"', apos: "'", lt: "<", gt: ">" })[name.toLowerCase()]);
}

export function appleFormat(value) {
  return xml(value).replace(/\\'/g, "'").replace(/\\"/g, '"').replace(/\\n/g, "\n")
    .replace(/%(\d+\$)?,?s/g, (_, position = "") => `%${position}@`)
    .replace(/%(\d+\$)?,?d/g, (_, position = "") => `%${position}ld`);
}

export function localization(language) {
  const directory = language === "en" ? "values" : `values-${language}`;
  const source = readFileSync(join(root, "apps", "android", "app", "src", "main", "res", directory, "strings.xml"), "utf8");
  const strings = new Map([...source.matchAll(/<string\b[^>]*\bname="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)]
    .map(([, key, value]) => [key, appleFormat(value)]));
  const plurals = new Map([...source.matchAll(/<plurals name="([^"]+)">([\s\S]*?)<\/plurals>/g)]
    .map(([, key, body]) => [key, Object.fromEntries([...body.matchAll(/<item quantity="([^"]+)">([\s\S]*?)<\/item>/g)]
      .map(([, quantity, value]) => [quantity, appleFormat(value)]))]));
  if (language !== "en") {
    const base = localization("en");
    for (const [key, value] of base.strings) if (!strings.has(key)) strings.set(key, value);
    for (const [key, value] of base.plurals) if (!plurals.has(key)) plurals.set(key, value);
  }
  for (const [key, value] of Object.entries(overrides[language])) strings.set(key, value);
  for (const key of ["translate_with_google", "google_translation_attribution", "anki_added_count",
    "anki_permission_needed", "remove_translation_models", "remove_translation_models_message",
    "translation_models_removed", "translation_models_remove_failed"]) strings.delete(key);
  return { strings, plurals };
}

const escapeXML = (value) => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");

function pluralFile(plurals) {
  const rows = [...plurals].sort(([a], [b]) => a.localeCompare(b)).map(([key, quantities]) =>
    `  <key>${escapeXML(key)}</key><dict>\n    <key>NSStringLocalizedFormatKey</key><string>%#@count@</string>\n` +
    `    <key>count</key><dict><key>NSStringFormatSpecTypeKey</key><string>NSStringPluralRuleType</string>\n` +
    `      <key>NSStringFormatValueTypeKey</key><string>ld</string>\n` +
    Object.entries(quantities).map(([quantity, value]) =>
      `      <key>${quantity}</key><string>${escapeXML(value.replace(/%\d+\$/g, "%"))}</string>\n`).join("") +
    `    </dict>\n  </dict>\n`).join("");
  return `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>\n${rows}</dict></plist>\n`;
}

function crc32(data) {
  let value = 0xffffffff;
  for (const byte of data) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  }
  return (value ^ 0xffffffff) >>> 0;
}

function chunk(name, data) {
  const type = Buffer.from(name);
  const result = Buffer.alloc(12 + data.length);
  result.writeUInt32BE(data.length); type.copy(result, 4); data.copy(result, 8);
  result.writeUInt32BE(crc32(Buffer.concat([type, data])), result.length - 4);
  return result;
}

function icon() {
  const source = readFileSync(join(root, "apps", "android", "app", "src", "main", "res", "drawable", "ic_launcher_foreground.xml"), "utf8");
  const shape = source.match(/android:pathData="([^"]+)"/)?.[1];
  if (!shape || /[^MLZ\d,.\s-]/.test(shape)) throw new Error("The shared M icon uses unsupported geometry.");
  const points = [...shape.matchAll(/[ML](-?[\d.]+),(-?[\d.]+)/g)].map(([, x, y]) => [Number(x), Number(y)]);
  const size = 1024;
  const rows = Buffer.alloc(size * (size * 3 + 1));
  function inside(x, y) {
    let result = false;
    for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
      const [ax, ay] = points[i], [bx, by] = points[j];
      if ((ay > y) !== (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) result = !result;
    }
    return result;
  }
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    let coverage = 0;
    for (const dy of [0.25, 0.75]) for (const dx of [0.25, 0.75]) {
      if (inside((x + dx) * 108 / size, (y + dy) * 108 / size)) coverage++;
    }
    const position = y * (size * 3 + 1) + 1 + x * 3;
    for (let channel = 0; channel < 3; channel++) {
      rows[position + channel] = Math.round(21 + ((channel === 2 ? 248 : 250) - 21) * coverage / 4);
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 2;
  return Buffer.concat([Buffer.from("89504e470d0a1a0a", "hex"), chunk("IHDR", header),
    chunk("IDAT", deflateSync(rows, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

export function generatedResources() {
  const files = new Map();
  for (const language of ["en", "fr", "es"]) {
    const { strings, plurals } = localization(language);
    files.set(join(output, `${language}.lproj`, "Localizable.strings"),
      Buffer.from([...strings].sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) =>
        `${JSON.stringify(key)} = ${JSON.stringify(value)};`).join("\n") + "\n"));
    files.set(join(output, `${language}.lproj`, "Localizable.stringsdict"), Buffer.from(pluralFile(plurals)));
  }
  const assets = join(output, "Assets.xcassets");
  files.set(join(assets, "Contents.json"), Buffer.from('{"info":{"author":"xcode","version":1}}\n'));
  files.set(join(assets, "AppIcon.appiconset", "Contents.json"), Buffer.from(JSON.stringify({
    images: [{ filename: "AppIcon.png", idiom: "universal", platform: "ios", size: "1024x1024" }],
    info: { author: "xcode", version: 1 }
  }, null, 2) + "\n"));
  files.set(join(assets, "AppIcon.appiconset", "AppIcon.png"), icon());
  files.set(join(assets, "AppBackground.colorset", "Contents.json"), Buffer.from(JSON.stringify({
    colors: [
      { idiom: "universal", color: { "color-space": "srgb", components: { red: "0.980392", green: "0.980392", blue: "0.968627", alpha: "1.000" } } },
      { idiom: "universal", appearances: [{ appearance: "luminosity", value: "dark" }],
        color: { "color-space": "srgb", components: { red: "0.082353", green: "0.082353", blue: "0.082353", alpha: "1.000" } } }
    ], info: { author: "xcode", version: 1 }
  }, null, 2) + "\n"));
  return files;
}

export function prepareResources(verify = false) {
  const files = generatedResources();
  for (const [file, bytes] of files) {
    if (verify) {
      if (!existsSync(file) || !readFileSync(file).equals(bytes)) throw new Error(`Stale iOS resource: ${file}. Run npm run ios:prepare.`);
    } else if (!existsSync(file) || !readFileSync(file).equals(bytes)) {
      mkdirSync(dirname(file), { recursive: true });
      writeFileSync(file, bytes);
    }
  }
  console.log(`iOS resources ${verify ? "verified" : "prepared"}: ${files.size} files, three locales and the shared M app icon.`);
  return [...files.values()].reduce((sum, bytes) => sum + bytes.length, 0);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const command = process.argv[2] ?? "prepare";
  if (!["prepare", "verify"].includes(command)) throw new Error("Use prepare or verify.");
  prepareResources(command === "verify");
}
