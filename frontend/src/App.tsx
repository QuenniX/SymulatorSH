import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import TestListPage from './pages/TestListPage';
import NewTestPage from './pages/NewTestPage';
import TestDetailsPage from './pages/TestDetailsPage';
import KreatorPage from './pages/KreatorPage';
import PricesPage from './pages/PricesPage';
import BatchRunnerPage from './pages/BatchRunnerPage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<TestListPage />} />
        <Route path="new" element={<NewTestPage />} />
        <Route path="kreator" element={<KreatorPage />} />
        <Route path="tests/:id" element={<TestDetailsPage />} />
        <Route path="prices" element={<PricesPage />} />
        <Route path="batch" element={<BatchRunnerPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
