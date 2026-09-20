export type RuleType = "HOURLY";

export interface ZoneResponse {
  id: number;
  name: string;
  city: string;
  hourlyRate: number;
  currency: string;
  ruleType: RuleType;
}