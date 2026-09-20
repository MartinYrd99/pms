const sofiaDateTimeFormatter = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/Sofia",
  dateStyle: "short",
  timeStyle: "medium",
});

export function formatSofiaDateTime(isoInstant: string): string {
  return sofiaDateTimeFormatter.format(new Date(isoInstant));
}