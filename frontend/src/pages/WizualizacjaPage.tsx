import { Suspense, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Canvas } from '@react-three/fiber';
import { OrbitControls, Sky, Stats } from '@react-three/drei';
import { getTest, listRooms } from '../api';
import type { Room, TestResponse } from '../types';
import Apartment from '../three/Apartment';

/**
 * Strona wizualizacji 3D pojedynczego testu.
 * Pokazuje mieszkanie z góry (izometryczna kamera), pokoje z urządzeniami.
 * Kamera obracalna myszką (drag), scroll = zoom.
 */
export default function WizualizacjaPage() {
  const { id } = useParams<{ id: string }>();
  const [test, setTest] = useState<TestResponse | null>(null);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    Promise.all([getTest(id), listRooms()])
      .then(([t, r]) => {
        setTest(t);
        setRooms(r);
      })
      .catch((err) => {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
      });
  }, [id]);

  const devices = useMemo(() => {
    if (!test) return [];
    const cfg = test.config as {
      devices?: Array<{ id: string; type: string; room?: string }>;
    };
    return cfg.devices ?? [];
  }, [test]);

  if (error) {
    return (
      <div>
        <Link to="/" className="text-brand-500 hover:underline">← Powrót do listy</Link>
        <div className="bg-red-900/50 border border-red-700 text-red-200 p-4 rounded mt-4">
          {error}
        </div>
      </div>
    );
  }

  if (!test) {
    return (
      <div>
        <Link to="/" className="text-brand-500 hover:underline">← Powrót do listy</Link>
        <p className="text-slate-400 mt-4">Wczytywanie wizualizacji...</p>
      </div>
    );
  }

  return (
    <div>
      <Link to={`/tests/${id}`} className="text-brand-500 hover:underline text-sm">
        ← Powrót do szczegółów testu
      </Link>

      <div className="mt-2 mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-100">Wizualizacja 3D</h1>
          <p className="text-sm text-slate-400 mt-1">{test.name}</p>
        </div>
        <div className="text-xs text-slate-500">
          {devices.length} urządzeń w {new Set(devices.map((d) => d.room).filter(Boolean)).size} pomieszczeniach
        </div>
      </div>

      <div className="bg-slate-900 border border-slate-700 rounded overflow-hidden" style={{ height: '70vh' }}>
        <Canvas
          shadows
          camera={{ position: [8, 9, 10], fov: 45 }}
          gl={{ antialias: true, toneMappingExposure: 1.2 }}
        >
          <color attach="background" args={['#0f172a']} />
          <Suspense fallback={null}>
            {/* Oświetlenie sceny */}
            <ambientLight intensity={0.55} />
            <directionalLight
              position={[8, 12, 6]}
              intensity={1.4}
              castShadow
              shadow-mapSize-width={2048}
              shadow-mapSize-height={2048}
              shadow-camera-left={-12}
              shadow-camera-right={12}
              shadow-camera-top={12}
              shadow-camera-bottom={-12}
              shadow-camera-near={0.1}
              shadow-camera-far={40}
            />
            {/* Fill light z drugiej strony */}
            <directionalLight
              position={[-5, 8, -6]}
              intensity={0.4}
              color="#93c5fd"
            />

            {/* Niebo za oknami */}
            <Sky
              distance={450000}
              sunPosition={[10, 8, 5]}
              inclination={0.4}
              azimuth={0.25}
            />

            {/* Mieszkanie */}
            <Apartment devices={devices} rooms={rooms} />

            {/* Kontrolki kamery - startuje z lekkim przechyłem */}
            <OrbitControls
              enableDamping
              dampingFactor={0.1}
              minDistance={4}
              maxDistance={25}
              maxPolarAngle={Math.PI / 2 - 0.05}
              target={[0, 0, 0]}
            />
          </Suspense>
        </Canvas>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-2 text-xs text-slate-400">
        <div className="bg-slate-800 border border-slate-700 rounded p-2 text-center">
          <strong className="text-slate-200">Obracanie</strong>: lewy przycisk + drag
        </div>
        <div className="bg-slate-800 border border-slate-700 rounded p-2 text-center">
          <strong className="text-slate-200">Przesuwanie</strong>: prawy przycisk + drag
        </div>
        <div className="bg-slate-800 border border-slate-700 rounded p-2 text-center">
          <strong className="text-slate-200">Zoom</strong>: scroll
        </div>
      </div>
    </div>
  );
}
