export interface RegisterRequest {
  username: string;
  password: string;
}

export interface RegisterResponse {
  id: number;
  username: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AccessTokenResponse {
  accessToken: string;
}
