#!/usr/bin/env node
/**
 * Publishes the launcher APK to the user's Drive (Retro/Config/launcher) so the stick offers the update
 * (Updater.kt). Usage, from android/:
 *
 *   ./gradlew assembleDebug && node publish-update.mjs [--force]
 *
 * Uses the stick's own OAuth client (local.properties, drive.file scope): the first run shows a code to
 * approve at google.com/device with the same Google account; the refresh token stays in .publish-token.json
 * (git-ignored). Bump versionCode in app/build.gradle.kts for every release: the stick only installs newer ones.
 */
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const APK = join(here, 'app/build/outputs/apk/debug/app-debug.apk')
const META = join(here, 'app/build/outputs/apk/debug/output-metadata.json')
const TOKEN_FILE = join(here, '.publish-token.json')
const SCOPE = 'https://www.googleapis.com/auth/drive.file'
const DRIVE = 'https://www.googleapis.com/drive/v3'
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3'
const FOLDER_MIME = 'application/vnd.google-apps.folder'
const force = process.argv.includes('--force')

function localProperties() {
  const props = {}
  for (const line of readFileSync(join(here, 'local.properties'), 'utf8').split(/\r?\n/)) {
    const m = /^\s*([\w.]+)\s*=\s*(.*?)\s*$/.exec(line)
    if (m) props[m[1]] = m[2]
  }
  return props
}

const props = localProperties()
const client = { id: props['retrostick.clientId'], secret: props['retrostick.clientSecret'] }
if (!client.id || !client.secret) throw new Error('Faltan retrostick.clientId / retrostick.clientSecret en local.properties')

async function form(url, params) {
  const res = await fetch(url, { method: 'POST', body: new URLSearchParams(params) })
  return { status: res.status, json: await res.json() }
}

/** Device flow, once: prints the code to approve on the phone. */
async function authorize() {
  const { json: device } = await form('https://oauth2.googleapis.com/device/code', { client_id: client.id, scope: SCOPE })
  if (!device.device_code) throw new Error(`device/code: ${JSON.stringify(device)}`)
  console.log(`\nAbre ${device.verification_url} e introduce el código ${device.user_code}`)
  console.log('(con la misma cuenta de Google que usa el stick)\n')
  let interval = (device.interval ?? 5) * 1000
  for (;;) {
    await new Promise((r) => setTimeout(r, interval))
    const { json } = await form('https://oauth2.googleapis.com/token', {
      client_id: client.id,
      client_secret: client.secret,
      device_code: device.device_code,
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
    })
    if (json.refresh_token) {
      writeFileSync(TOKEN_FILE, JSON.stringify({ refresh_token: json.refresh_token }))
      return json.access_token
    }
    if (json.error === 'slow_down') interval += 5000
    else if (json.error !== 'authorization_pending') throw new Error(`token: ${json.error}`)
  }
}

async function accessToken() {
  if (!existsSync(TOKEN_FILE)) return authorize()
  const { refresh_token } = JSON.parse(readFileSync(TOKEN_FILE, 'utf8'))
  const { json } = await form('https://oauth2.googleapis.com/token', {
    client_id: client.id,
    client_secret: client.secret,
    refresh_token,
    grant_type: 'refresh_token',
  })
  if (json.access_token) return json.access_token
  console.log('La autorización guardada ya no vale: hay que aprobarla otra vez.')
  return authorize()
}

const token = await accessToken()
async function api(method, url, body, headers = {}) {
  const res = await fetch(url, { method, body, headers: { Authorization: `Bearer ${token}`, ...headers } })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${url.split('?')[0]}: ${res.status} ${text}`)
  return text ? JSON.parse(text) : null
}

async function children(parent, name) {
  const q = encodeURIComponent(`'${parent}' in parents and name = '${name}' and trashed = false`)
  const { files } = await api('GET', `${DRIVE}/files?q=${q}&fields=files(id,name,mimeType)&spaces=drive`)
  return files
}

/** Folder id of a "Retro/..." path, created when missing (same tree the stick and the phone app use). */
async function folder(path) {
  let parent = 'root'
  for (const name of path.split('/')) {
    const found = (await children(parent, name)).find((f) => f.mimeType === FOLDER_MIME)
    parent = found
      ? found.id
      : (await api('POST', `${DRIVE}/files?fields=id`, JSON.stringify({ name, mimeType: FOLDER_MIME, parents: [parent] }), {
          'Content-Type': 'application/json',
        })).id
  }
  return parent
}

/** Creates or replaces (keeping the file id) a file in the folder. */
async function put(parent, name, data, mime) {
  const existing = (await children(parent, name)).find((f) => f.mimeType !== FOLDER_MIME)
  const boundary = 'rs' + Date.now().toString(36)
  const meta = existing ? { name } : { name, parents: [parent] }
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n`),
    Buffer.from(`--${boundary}\r\nContent-Type: ${mime}\r\n\r\n`),
    data,
    Buffer.from(`\r\n--${boundary}--`),
  ])
  const url = existing ? `${UPLOAD}/files/${existing.id}?uploadType=multipart` : `${UPLOAD}/files?uploadType=multipart`
  return api(existing ? 'PATCH' : 'POST', url, body, { 'Content-Type': `multipart/related; boundary=${boundary}` })
}

const meta = JSON.parse(readFileSync(META, 'utf8')).elements[0]
const apk = readFileSync(APK)
const release = {
  versionCode: meta.versionCode,
  versionName: meta.versionName,
  apk: 'launcher.apk',
  sha256: createHash('sha256').update(apk).digest('hex'),
  size: apk.length,
  published: new Date().toISOString(),
}

const dir = await folder('Retro/Config/launcher')
const current = (await children(dir, 'launcher.json'))[0]
if (current && !force) {
  const res = await fetch(`${DRIVE}/files/${current.id}?alt=media`, { headers: { Authorization: `Bearer ${token}` } })
  const published = await res.json()
  if (published.versionCode >= release.versionCode) {
    throw new Error(
      `Drive ya tiene la versión ${published.versionName} (${published.versionCode}): sube versionCode en app/build.gradle.kts (o usa --force)`,
    )
  }
}
// APK first: the stick only looks for it once launcher.json announces it.
await put(dir, release.apk, apk, 'application/vnd.android.package-archive')
await put(dir, 'launcher.json', Buffer.from(JSON.stringify(release, null, 2)), 'application/json')
console.log(`Publicada la versión ${release.versionName} (${release.versionCode}, ${(apk.length / 1048576).toFixed(1)} MB) en Retro/Config/launcher`)
