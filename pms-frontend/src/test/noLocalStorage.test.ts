import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const thisFile = fileURLToPath(import.meta.url);
const srcDir = dirname(dirname(thisFile));

function listSourceFiles(dir: string): string[] {
  const entries = readdirSync(dir);
  const files: string[] = [];

  for (const entry of entries) {
    const fullPath = join(dir, entry);
    if (statSync(fullPath).isDirectory()) {
      files.push(...listSourceFiles(fullPath));
      continue;
    }
    if (/\.(ts|tsx)$/.test(entry)) {
      files.push(fullPath);
    }
  }

  return files;
}

describe("localStorage", () => {
  it("is never read or written anywhere under src/, now that the access token lives in memory and the refresh token is an HttpOnly cookie", () => {
    // Excludes this file itself, which necessarily mentions the forbidden word in its own title.
    const offenders = listSourceFiles(srcDir)
      .filter((file) => file !== thisFile)
      .filter((file) => readFileSync(file, "utf-8").includes("localStorage"));

    expect(offenders).toEqual([]);
  });
});
