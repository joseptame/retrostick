/**
 * Google sign-in with a code (device flow), using the same OAuth client as the stick ("TVs and Limited
 * Input devices"). With the drive.file scope both then see the same files (README 7.3).
 * Google CORS-enables the device/token endpoints. The client secret of this client type is not
 * confidential (developers.google.com/identity/protocols/oauth2/limited-input-device).
 */
const CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string
const CLIENT_SECRET = import.meta.env.VITE_GOOGLE_CLIENT_SECRET as string
const SCOPE = 'https://www.googleapis.com/auth/drive.file'
const STORAGE_KEY = 'retrostick.auth'

export interface DeviceCode {
  deviceCode: string
  userCode: string
  verificationUrl: string
  expiresAt: number
  interval: number
}

interface Stored {
  refreshToken: string
  email?: string
}

let access: { token: string; expiresAt: number } | null = null

function stored(): Stored | null {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null')
  } catch {
    return null
  }
}

export const isConfigured = () => Boolean(CLIENT_ID && CLIENT_SECRET)
export const isSignedIn = () => stored() !== null
export const accountEmail = () => stored()?.email ?? null

export function setAccountEmail(email: string) {
  const s = stored()
  if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...s, email }))
}

export function signOut() {
  localStorage.removeItem(STORAGE_KEY)
  access = null
}

async function post(url: string, params: Record<string, string>) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  })
  return { status: res.status, json: await res.json().catch(() => ({})) }
}

export async function requestDeviceCode(): Promise<DeviceCode> {
  const { status, json } = await post('https://oauth2.googleapis.com/device/code', { client_id: CLIENT_ID, scope: SCOPE })
  if (status !== 200) throw new Error(json.error ?? `HTTP ${status}`)
  return {
    deviceCode: json.device_code,
    userCode: json.user_code,
    verificationUrl: json.verification_url,
    expiresAt: Date.now() + json.expires_in * 1000,
    interval: json.interval ?? 5,
  }
}

export type LoginOutcome = 'ok' | 'denied' | 'expired' | 'cancelled'

/** Polls until the user accepts on google.com/device. */
export async function waitForLogin(code: DeviceCode, signal: AbortSignal): Promise<LoginOutcome> {
  let interval = code.interval
  while (Date.now() < code.expiresAt) {
    await new Promise((r) => setTimeout(r, interval * 1000))
    if (signal.aborted) return 'cancelled'
    const { status, json } = await post('https://oauth2.googleapis.com/token', {
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      device_code: code.deviceCode,
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
    })
    if (status === 200) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ refreshToken: json.refresh_token } satisfies Stored))
      access = { token: json.access_token, expiresAt: Date.now() + json.expires_in * 1000 }
      return 'ok'
    }
    if (json.error === 'authorization_pending') continue
    if (json.error === 'slow_down') interval += 5
    else if (json.error === 'access_denied') return 'denied'
    else throw new Error(json.error ?? `HTTP ${status}`)
  }
  return 'expired'
}

export class SignedOutError extends Error {}

let refreshing: Promise<string> | null = null

export async function accessToken(): Promise<string> {
  if (access && Date.now() < access.expiresAt - 60_000) return access.token
  refreshing ??= (async () => {
    const s = stored()
    if (!s) throw new SignedOutError('Sesión cerrada')
    const { status, json } = await post('https://oauth2.googleapis.com/token', {
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      refresh_token: s.refreshToken,
      grant_type: 'refresh_token',
    })
    if (status !== 200) {
      // Revoked: the user has to sign in again ("Volver a iniciar sesión", README 7.3)
      if (json.error === 'invalid_grant') signOut()
      throw json.error === 'invalid_grant' ? new SignedOutError('Sesión caducada') : new Error(json.error ?? `HTTP ${status}`)
    }
    access = { token: json.access_token, expiresAt: Date.now() + json.expires_in * 1000 }
    return access.token
  })().finally(() => (refreshing = null))
  return refreshing
}
