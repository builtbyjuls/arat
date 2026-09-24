import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const forbiddenText = [
  'arat-local-owner-token',
  'arat-local-member-token',
  'arat-local-outsider-token',
  'arat-local-provider-token',
  'arat-local-operator-token',
  'Local development only',
  'Choose a local actor',
];

const output = await readTree('dist/ui');
for (const text of forbiddenText) {
  if (output.includes(text)) {
    throw new Error(`Production build contains local-demo text: ${text}`);
  }
}

async function readTree(path) {
  const entries = await readdir(path, { withFileTypes: true });
  const files = await Promise.all(entries.map(async (entry) => entry.isDirectory()
    ? readTree(join(path, entry.name))
    : readFile(join(path, entry.name), 'utf8')));
  return files.join('');
}
