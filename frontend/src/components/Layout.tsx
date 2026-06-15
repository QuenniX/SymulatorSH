import { Link, Outlet, useLocation } from 'react-router-dom';

export default function Layout() {
  const location = useLocation();
  const isActive = (path: string) =>
    location.pathname === path || (path !== '/' && location.pathname.startsWith(path));

  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-slate-800 border-b border-slate-700">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <Link to="/" className="text-xl font-bold text-brand-500 hover:text-brand-600">
            SymulatorSH
          </Link>
          <nav className="flex gap-4">
            <Link
              to="/"
              className={`px-3 py-1.5 rounded text-sm font-medium transition ${
                isActive('/') && location.pathname === '/'
                  ? 'bg-brand-600 text-white'
                  : 'text-slate-300 hover:bg-slate-700'
              }`}
            >
              Lista testów
            </Link>
            <Link
              to="/new"
              className={`px-3 py-1.5 rounded text-sm font-medium transition ${
                isActive('/new')
                  ? 'bg-brand-600 text-white'
                  : 'text-slate-300 hover:bg-slate-700'
              }`}
            >
              Nowy test
            </Link>
            <a
              href="http://localhost:8080/swagger-ui.html"
              target="_blank"
              rel="noreferrer"
              className="px-3 py-1.5 rounded text-sm font-medium text-slate-400 hover:bg-slate-700"
            >
              API
            </a>
          </nav>
        </div>
      </header>

      <main className="flex-1 max-w-6xl mx-auto w-full px-6 py-8">
        <Outlet />
      </main>

      <footer className="border-t border-slate-700 py-4 text-center text-sm text-slate-500">
        Smart Home Test Platform · Praca magisterska Igor Guła · WEiI PRz
      </footer>
    </div>
  );
}
