import { describe, expect, it } from "vitest";
import type { RouteMatchCallback } from "workbox-core/types";
import { pwaOptions } from "./pwaOptions";

const API_SAMPLE_URLS = [
  "https://pms.example.com/api/v1/parking-sessions/active",
  "https://pms.example.com/api/v1/zones",
  "http://localhost/api/v1/payments/1",
];

// This config never defines a urlPattern as a callback (see pwaOptions.ts), only RegExp/string
// — so that is all this assertion needs to understand.
function matchesUrl(pattern: RegExp | string | RouteMatchCallback, sampleUrl: string): boolean {
  if (pattern instanceof RegExp) {
    return pattern.test(sampleUrl);
  }
  if (typeof pattern === "string") {
    return sampleUrl.includes(pattern);
  }
  throw new Error("Unexpected callback urlPattern — extend this test to cover it.");
}

describe("pwaOptions manifest: installable app shell", () => {
  const manifest = pwaOptions.manifest;
  if (!manifest) {
    throw new Error("Expected pwaOptions.manifest to be configured");
  }

  it("names the app and gives it a short name", () => {
    expect(manifest.name).toBe("Parking Management System");
    expect(manifest.short_name).toBe("Parking");
  });

  it("declares a standalone display launched from /", () => {
    expect(manifest.display).toBe("standalone");
    expect(manifest.start_url).toBe("/");
  });

  it("includes both the 192x192 and 512x512 icons", () => {
    const sizes = (manifest.icons ?? []).map((icon) => icon.sizes);
    expect(sizes).toContain("192x192");
    expect(sizes).toContain("512x512");
  });
});

describe("pwaOptions workbox: /api/v1 stays network-only", () => {
  const workbox = pwaOptions.workbox ?? {};
  const runtimeCaching = workbox.runtimeCaching ?? [];

  it("has no runtime-caching rule that matches an /api/v1/... URL", () => {
    for (const rule of runtimeCaching) {
      for (const sampleUrl of API_SAMPLE_URLS) {
        expect(matchesUrl(rule.urlPattern, sampleUrl)).toBe(false);
      }
    }
  });

  it("configures no background-sync/queue plugin on any runtime-caching rule", () => {
    for (const rule of runtimeCaching) {
      for (const plugin of rule.options?.plugins ?? []) {
        const pluginName = plugin.constructor?.name ?? "";
        expect(pluginName).not.toMatch(/BackgroundSync|Queue/i);
      }
    }
  });

  it("excludes /api/v1/ from the navigation fallback", () => {
    const denylist = workbox.navigateFallbackDenylist ?? [];
    expect(denylist.length).toBeGreaterThan(0);
    for (const sampleUrl of API_SAMPLE_URLS) {
      const { pathname } = new URL(sampleUrl);
      expect(denylist.some((pattern) => pattern.test(pathname))).toBe(true);
    }
  });
});
