import { readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { Language, Parser } from "web-tree-sitter";

const require = createRequire(import.meta.url);
const root = fileURLToPath(new URL("../", import.meta.url));
let language;

export async function swiftSyntaxErrors(source) {
  if (!language) {
    await Parser.init();
    language = await Language.load(require.resolve("@repomix/tree-sitter-wasms/out/tree-sitter-swift.wasm"));
  }
  const parser = new Parser();
  parser.setLanguage(language);
  const tree = parser.parse(source);
  if (!tree) throw new Error("The Swift syntax parser returned no tree.");
  const errors = [];
  function visit(node) {
    const before = errors.length;
    for (const child of node.children) visit(child);
    if ((node.type === "ERROR" || node.isMissing) && errors.length === before) {
      errors.push({ line: node.startPosition.row + 1, column: node.startPosition.column + 1,
        message: node.isMissing ? `Missing ${node.type}` : source.slice(node.startIndex, node.endIndex).trim().slice(0, 100) });
    }
  }
  try { visit(tree.rootNode); }
  finally { tree.delete(); parser.delete(); }
  return errors;
}

export function swiftFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? swiftFiles(path) : entry.name.endsWith(".swift") ? [path] : [];
  }).sort();
}

export async function checkSwiftSources() {
  const directories = ["MaxFluency", "MaxFluencyTests", "MaxFluencyUITests"];
  const files = directories.flatMap((name) => swiftFiles(join(root, "apps", "ios", name)));
  if (!files.length) throw new Error("No native Swift sources were found.");
  const failures = [];
  for (const file of files) {
    for (const error of await swiftSyntaxErrors(readFileSync(file, "utf8"))) {
      failures.push(`${file}:${error.line}:${error.column}: ${error.message}`);
    }
  }
  if (failures.length) throw new Error(`Swift syntax errors:\n${failures.join("\n")}`);
  console.log(`Swift syntax checked locally: ${files.length} files. This is not an Apple SDK type-check or Xcode build.`);
  return files.length;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await checkSwiftSources();
