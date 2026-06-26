import { http } from './http'
import type { AuthResponse, GlobalRole, UserResponse } from '@/types/api'

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload extends LoginPayload {
  email?: string
}

export interface CreateUserPayload extends RegisterPayload {
  globalRole: GlobalRole
}

export async function login(payload: LoginPayload) {
  const { data } = await http.post<AuthResponse>('/auth/login', payload)
  return data
}

export async function register(payload: RegisterPayload) {
  const { data } = await http.post<AuthResponse>('/auth/register', payload)
  return data
}

export async function refresh(refreshToken: string) {
  const { data } = await http.post<AuthResponse>('/auth/refresh', { refreshToken })
  return data
}

export async function logout(refreshToken: string) {
  await http.post('/auth/logout', { refreshToken })
}

export async function listUsers() {
  const { data } = await http.get<UserResponse[]>('/auth/users')
  return data
}

export async function createUser(payload: CreateUserPayload) {
  const { data } = await http.post<UserResponse>('/auth/users', payload)
  return data
}

export async function updateUserRole(userId: number, globalRole: GlobalRole) {
  const { data } = await http.put<UserResponse>(`/auth/users/${userId}/role`, { globalRole })
  return data
}
