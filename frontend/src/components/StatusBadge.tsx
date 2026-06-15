import type { TestStatus } from '../types';

const STATUS_STYLES: Record<TestStatus, string> = {
  QUEUED:    'bg-slate-600 text-slate-100',
  RUNNING:   'bg-blue-600 text-white',
  COMPLETED: 'bg-emerald-600 text-white',
  FAILED:    'bg-red-600 text-white',
  CANCELLED: 'bg-amber-600 text-white',
};

const STATUS_LABELS: Record<TestStatus, string> = {
  QUEUED:    'Kolejka',
  RUNNING:   'W trakcie',
  COMPLETED: 'Zakończony',
  FAILED:    'Błąd',
  CANCELLED: 'Anulowany',
};

interface Props {
  status: TestStatus;
}

export default function StatusBadge({ status }: Props) {
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold ${STATUS_STYLES[status]}`}>
      {status === 'RUNNING' && (
        <span className="w-1.5 h-1.5 bg-white rounded-full mr-1.5 animate-pulse" />
      )}
      {STATUS_LABELS[status]}
    </span>
  );
}
