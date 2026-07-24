import axios from 'axios';
import type {
  CreateTemplateResponse,
  CreateTestResponse,
  DeviceType,
  MeasurementsResponse,
  Room,
  TemplateResponse,
  TemplateSummary,
  TestResponse,
  TestSummary,
} from './types';

const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

export async function listTests(): Promise<TestSummary[]> {
  const res = await api.get<TestSummary[]>('/tests');
  return res.data;
}

export async function getTest(id: string): Promise<TestResponse> {
  const res = await api.get<TestResponse>(`/tests/${id}`);
  return res.data;
}

export async function createTest(config: unknown): Promise<CreateTestResponse> {
  const res = await api.post<CreateTestResponse>('/tests', config);
  return res.data;
}

export async function deleteTest(id: string): Promise<void> {
  await api.delete(`/tests/${id}`);
}

export async function getMeasurements(
  id: string,
  deviceId?: string,
): Promise<MeasurementsResponse> {
  const params = deviceId ? { device_id: deviceId } : undefined;
  const res = await api.get<MeasurementsResponse>(`/tests/${id}/measurements`, { params });
  return res.data;
}

export async function listDeviceTypes(): Promise<DeviceType[]> {
  const res = await api.get<DeviceType[]>('/device-types');
  return res.data;
}

export async function listRooms(): Promise<Room[]> {
  const res = await api.get<Room[]>('/rooms');
  return res.data;
}

export async function listTemplates(): Promise<TemplateSummary[]> {
  const res = await api.get<TemplateSummary[]>('/templates');
  return res.data;
}

export async function getTemplate(id: string): Promise<TemplateResponse> {
  const res = await api.get<TemplateResponse>(`/templates/${id}`);
  return res.data;
}

export async function createTemplate(
  name: string,
  description: string | null,
  config: unknown,
): Promise<CreateTemplateResponse> {
  const res = await api.post<CreateTemplateResponse>('/templates', {
    name,
    description,
    config,
  });
  return res.data;
}

export async function deleteTemplate(id: string): Promise<void> {
  await api.delete(`/templates/${id}`);
}
