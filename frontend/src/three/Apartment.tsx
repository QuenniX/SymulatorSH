import { useRef } from 'react';
import { Text } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import type { Mesh, MeshStandardMaterial } from 'three';
import type { Room } from '../types';

/**
 * Layout mieszkania 9×6m w wariancie open-plan.
 * Zamiast wewnętrznych ścian mamy różne kolory podłóg oznaczające strefy.
 * Jedna zewnętrzna ściana obiega mieszkanie po obwodzie.
 */
const APT_MIN_X = -4.5;
const APT_MAX_X = 4.5;
const APT_MIN_Z = -3;
const APT_MAX_Z = 3;
const WALL_HEIGHT = 1.4;
const WALL_THICK = 0.2;

interface Zone {
  type: string;
  label: string;
  bounds: [number, number, number, number];
  floorColor: string;
}

const ZONES: Zone[] = [
  { type: 'KITCHEN', label: 'Kuchnia', bounds: [-4.5, -3, -1.5, 0], floorColor: '#e8b878' },
  { type: 'BATHROOM', label: 'Łazienka', bounds: [-1.5, -3, 0.5, 0], floorColor: '#b8d4e0' },
  { type: 'LIVING_ROOM', label: 'Salon', bounds: [0.5, -3, 4.5, 3], floorColor: '#c19a6b' },
  { type: 'BEDROOM', label: 'Sypialnia', bounds: [-4.5, 0, -1.5, 3], floorColor: '#b4917a' },
  { type: 'HALLWAY', label: 'Przedpokój', bounds: [-1.5, 0, 0.5, 3], floorColor: '#a89684' },
];

function zoneCenter(z: Zone): [number, number] {
  const [x0, z0, x1, z1] = z.bounds;
  return [(x0 + x1) / 2, (z0 + z1) / 2];
}
function zoneSize(z: Zone): [number, number] {
  const [x0, z0, x1, z1] = z.bounds;
  return [x1 - x0, z1 - z0];
}

interface DeviceIn3D {
  id: string;
  type: string;
  room?: string;
}

interface ApartmentProps {
  devices: DeviceIn3D[];
  rooms: Room[];
  /** Aktywność każdego urządzenia (0-1) - skaluje efekty świetlne i animacje. */
  activityByDeviceId: Map<string, number>;
}

export default function Apartment({ devices, activityByDeviceId }: ApartmentProps) {
  return (
    <group>
      {/* Trawnik dookoła mieszkania - naturalny teren zamiast pustki */}
      <mesh position={[0, -0.03, 0]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[60, 60]} />
        <meshStandardMaterial color="#5c8a3f" roughness={0.95} />
      </mesh>

      {/* Chodnik / kafelki wokół mieszkania (bufor między podłogą a trawnikiem) */}
      <mesh position={[0, -0.02, 0]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[13, 10]} />
        <meshStandardMaterial color="#9ca3af" roughness={0.85} />
      </mesh>

      {/* Dekoracyjne krzewy w rogach */}
      <Bush position={[-6, 0, -4]} />
      <Bush position={[6, 0, -4]} />
      <Bush position={[-6, 0, 4]} />
      <Bush position={[6, 0, 4]} />

      {ZONES.map((z) => (
        <ZoneFloor key={z.type} zone={z} />
      ))}
      {ZONES.map((z) => (
        <ZoneFurniture key={`furn-${z.type}`} zone={z} />
      ))}
      <ExteriorWalls />

      {ZONES.map((z) => {
        const zoneDevices = devices.filter((d) => d.room === z.type);
        return zoneDevices.map((d, i) => (
          <DeviceInZone
            key={d.id}
            device={d}
            zone={z}
            indexInZone={i}
            totalInZone={zoneDevices.length}
            activity={activityByDeviceId.get(d.id) ?? 0}
          />
        ));
      })}
    </group>
  );
}

function ZoneFloor({ zone }: { zone: Zone }) {
  const [cx, cz] = zoneCenter(zone);
  const [w, d] = zoneSize(zone);
  return (
    <group>
      <mesh position={[cx, 0, cz]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[w, d]} />
        <meshStandardMaterial color={zone.floorColor} roughness={0.85} />
      </mesh>
      <Text
        position={[cx, 0.02, cz + d / 2 - 0.3]}
        rotation={[-Math.PI / 2, 0, 0]}
        fontSize={0.32}
        color="#1e293b"
        anchorX="center"
        anchorY="middle"
        outlineWidth={0.005}
        outlineColor="#ffffff"
      >
        {zone.label}
      </Text>
    </group>
  );
}

function ExteriorWalls() {
  const w = APT_MAX_X - APT_MIN_X;
  const d = APT_MAX_Z - APT_MIN_Z;
  const cx = (APT_MIN_X + APT_MAX_X) / 2;
  const cz = (APT_MIN_Z + APT_MAX_Z) / 2;
  const wallColor = '#f1f5f9';
  return (
    <group>
      <mesh position={[cx, WALL_HEIGHT / 2, APT_MIN_Z - WALL_THICK / 2]} castShadow receiveShadow>
        <boxGeometry args={[w + WALL_THICK, WALL_HEIGHT, WALL_THICK]} />
        <meshStandardMaterial color={wallColor} />
      </mesh>
      <mesh position={[cx, WALL_HEIGHT / 2, APT_MAX_Z + WALL_THICK / 2]} castShadow receiveShadow>
        <boxGeometry args={[w + WALL_THICK, WALL_HEIGHT, WALL_THICK]} />
        <meshStandardMaterial color={wallColor} />
      </mesh>
      <mesh position={[APT_MIN_X - WALL_THICK / 2, WALL_HEIGHT / 2, cz]} castShadow receiveShadow>
        <boxGeometry args={[WALL_THICK, WALL_HEIGHT, d]} />
        <meshStandardMaterial color={wallColor} />
      </mesh>
      <mesh position={[APT_MAX_X + WALL_THICK / 2, WALL_HEIGHT / 2, cz]} castShadow receiveShadow>
        <boxGeometry args={[WALL_THICK, WALL_HEIGHT, d]} />
        <meshStandardMaterial color={wallColor} />
      </mesh>
    </group>
  );
}

// ---- Meble w strefach (bez zmian) ------------------------------------------

function ZoneFurniture({ zone }: { zone: Zone }) {
  switch (zone.type) {
    case 'KITCHEN': return <KitchenFurniture zone={zone} />;
    case 'LIVING_ROOM': return <LivingRoomFurniture zone={zone} />;
    case 'BEDROOM': return <BedroomFurniture zone={zone} />;
    case 'BATHROOM': return <BathroomFurniture zone={zone} />;
    case 'HALLWAY': return <HallwayFurniture zone={zone} />;
    default: return null;
  }
}

function KitchenFurniture({ zone }: { zone: Zone }) {
  const [cx] = zoneCenter(zone);
  const [w] = zoneSize(zone);
  const blatY = 0.9;
  return (
    <group>
      <mesh position={[cx, blatY / 2, -3 + 0.3]} castShadow receiveShadow>
        <boxGeometry args={[w - 0.5, blatY, 0.55]} />
        <meshStandardMaterial color="#f5f5dc" />
      </mesh>
      <mesh position={[cx, blatY + 0.02, -3 + 0.3]} castShadow>
        <boxGeometry args={[w - 0.4, 0.04, 0.6]} />
        <meshStandardMaterial color="#3d2817" roughness={0.5} />
      </mesh>
      <mesh position={[cx + 0.5, blatY + 0.05, -3 + 0.3]}>
        <boxGeometry args={[0.5, 0.05, 0.4]} />
        <meshStandardMaterial color="#94a3b8" metalness={0.9} roughness={0.2} />
      </mesh>
      <mesh position={[cx, 0.4, -0.7]} castShadow receiveShadow>
        <boxGeometry args={[1.2, 0.08, 0.8]} />
        <meshStandardMaterial color="#8b6f47" />
      </mesh>
      {[[cx - 0.5, 0.2, -0.7 - 0.3], [cx + 0.5, 0.2, -0.7 - 0.3], [cx - 0.5, 0.2, -0.7 + 0.3], [cx + 0.5, 0.2, -0.7 + 0.3]].map((p, i) => (
        <mesh key={i} position={p as [number, number, number]}>
          <boxGeometry args={[0.06, 0.4, 0.06]} />
          <meshStandardMaterial color="#5d4a2f" />
        </mesh>
      ))}
    </group>
  );
}

function LivingRoomFurniture({ zone }: { zone: Zone }) {
  const [cx, cz] = zoneCenter(zone);
  return (
    <group>
      <mesh position={[cx, 0.005, cz]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[3, 2]} />
        <meshStandardMaterial color="#8b5e3c" roughness={0.9} />
      </mesh>
      <mesh position={[4, 0.3, cz]} castShadow receiveShadow>
        <boxGeometry args={[0.7, 0.6, 2.5]} />
        <meshStandardMaterial color="#4b5563" />
      </mesh>
      <mesh position={[4.3, 0.7, cz]} castShadow receiveShadow>
        <boxGeometry args={[0.15, 0.5, 2.5]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
      <mesh position={[4, 0.65, cz - 1.35]} castShadow>
        <boxGeometry args={[0.7, 0.4, 0.2]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
      <mesh position={[4, 0.65, cz + 1.35]} castShadow>
        <boxGeometry args={[0.7, 0.4, 0.2]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
      <mesh position={[cx, 0.3, cz]} castShadow receiveShadow>
        <boxGeometry args={[1, 0.06, 0.6]} />
        <meshStandardMaterial color="#3d2817" />
      </mesh>
      {[[cx - 0.4, 0.15, cz - 0.2], [cx + 0.4, 0.15, cz - 0.2], [cx - 0.4, 0.15, cz + 0.2], [cx + 0.4, 0.15, cz + 0.2]].map((p, i) => (
        <mesh key={i} position={p as [number, number, number]}>
          <boxGeometry args={[0.06, 0.3, 0.06]} />
          <meshStandardMaterial color="#2d1a0c" />
        </mesh>
      ))}
      <mesh position={[1, 0.25, cz]} castShadow receiveShadow>
        <boxGeometry args={[0.4, 0.5, 1.5]} />
        <meshStandardMaterial color="#e5e7eb" />
      </mesh>
      {/* Mała szafeczka na router */}
      <mesh position={[1.2, 0.2, 2.5]} castShadow receiveShadow>
        <boxGeometry args={[0.5, 0.4, 0.4]} />
        <meshStandardMaterial color="#8b6f47" />
      </mesh>
      {[[1.2 - 0.22, 0.05, 2.5 - 0.17], [1.2 + 0.22, 0.05, 2.5 - 0.17], [1.2 - 0.22, 0.05, 2.5 + 0.17], [1.2 + 0.22, 0.05, 2.5 + 0.17]].map((p, i) => (
        <mesh key={i} position={p as [number, number, number]}>
          <boxGeometry args={[0.04, 0.1, 0.04]} />
          <meshStandardMaterial color="#5d4a2f" />
        </mesh>
      ))}
    </group>
  );
}

function BedroomFurniture({ zone }: { zone: Zone }) {
  const [cx, cz] = zoneCenter(zone);
  const bedZ = cz + 0.5;
  return (
    <group>
      <mesh position={[cx, 0.25, bedZ]} castShadow receiveShadow>
        <boxGeometry args={[1.6, 0.3, 2]} />
        <meshStandardMaterial color="#7c3aed" />
      </mesh>
      <mesh position={[cx, 0.5, bedZ]} castShadow>
        <boxGeometry args={[1.5, 0.15, 1.9]} />
        <meshStandardMaterial color="#f3f4f6" />
      </mesh>
      <mesh position={[cx - 0.35, 0.62, bedZ - 0.7]} castShadow>
        <boxGeometry args={[0.5, 0.1, 0.35]} />
        <meshStandardMaterial color="#ffffff" />
      </mesh>
      <mesh position={[cx + 0.35, 0.62, bedZ - 0.7]} castShadow>
        <boxGeometry args={[0.5, 0.1, 0.35]} />
        <meshStandardMaterial color="#ffffff" />
      </mesh>
      <mesh position={[cx, 0.63, bedZ + 0.2]} castShadow>
        <boxGeometry args={[1.5, 0.08, 1.3]} />
        <meshStandardMaterial color="#c084fc" />
      </mesh>
      <mesh position={[cx - 1.05, 0.3, bedZ - 0.7]} castShadow receiveShadow>
        <boxGeometry args={[0.4, 0.6, 0.4]} />
        <meshStandardMaterial color="#8b6f47" />
      </mesh>
      <mesh position={[cx + 1.05, 0.3, bedZ - 0.7]} castShadow receiveShadow>
        <boxGeometry args={[0.4, 0.6, 0.4]} />
        <meshStandardMaterial color="#8b6f47" />
      </mesh>
      <mesh position={[cx - 1.05, 0.72, bedZ - 0.7]} castShadow>
        <cylinderGeometry args={[0.08, 0.12, 0.2, 12]} />
        <meshStandardMaterial color="#fef3c7" emissive="#fbbf24" emissiveIntensity={0.3} />
      </mesh>
      <mesh position={[cx + 1.05, 0.72, bedZ - 0.7]} castShadow>
        <cylinderGeometry args={[0.08, 0.12, 0.2, 12]} />
        <meshStandardMaterial color="#fef3c7" emissive="#fbbf24" emissiveIntensity={0.3} />
      </mesh>
    </group>
  );
}

function BathroomFurniture({ zone }: { zone: Zone }) {
  const [cx, cz] = zoneCenter(zone);
  return (
    <group>
      <mesh position={[cx, 0.2, cz - 1]} castShadow receiveShadow>
        <boxGeometry args={[1.4, 0.4, 0.7]} />
        <meshStandardMaterial color="#f9fafb" />
      </mesh>
      <mesh position={[cx, 0.35, cz - 1]}>
        <boxGeometry args={[1.3, 0.15, 0.6]} />
        <meshStandardMaterial color="#dbeafe" />
      </mesh>
      <mesh position={[cx - 0.5, 0.55, cz + 1]} castShadow receiveShadow>
        <boxGeometry args={[0.55, 0.15, 0.4]} />
        <meshStandardMaterial color="#ffffff" />
      </mesh>
      <mesh position={[cx - 0.5, 0.25, cz + 1]} castShadow>
        <boxGeometry args={[0.5, 0.4, 0.35]} />
        <meshStandardMaterial color="#e5e7eb" />
      </mesh>
      <mesh position={[cx + 0.55, 0.2, cz + 1]} castShadow>
        <boxGeometry args={[0.35, 0.4, 0.5]} />
        <meshStandardMaterial color="#ffffff" />
      </mesh>
      <mesh position={[cx + 0.55, 0.42, cz + 1]}>
        <boxGeometry args={[0.35, 0.04, 0.5]} />
        <meshStandardMaterial color="#f3f4f6" />
      </mesh>
      <mesh position={[cx + 0.55, 0.7, cz + 1.15]} castShadow>
        <boxGeometry args={[0.3, 0.4, 0.15]} />
        <meshStandardMaterial color="#ffffff" />
      </mesh>
    </group>
  );
}

function HallwayFurniture({ zone }: { zone: Zone }) {
  const [cx, cz] = zoneCenter(zone);
  return (
    <group>
      <mesh position={[cx - 0.7, 0.3, cz]} castShadow receiveShadow>
        <boxGeometry args={[0.3, 0.6, 1.5]} />
        <meshStandardMaterial color="#8b6f47" />
      </mesh>
      <mesh position={[cx + 0.7, 0.8, cz]} castShadow>
        <cylinderGeometry args={[0.03, 0.03, 1.6, 8]} />
        <meshStandardMaterial color="#4b5563" />
      </mesh>
      <mesh position={[cx + 0.7, 0.05, cz]}>
        <cylinderGeometry args={[0.25, 0.3, 0.1, 16]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
      <mesh position={[cx + 0.55, 1.55, cz]}>
        <boxGeometry args={[0.3, 0.03, 0.03]} />
        <meshStandardMaterial color="#4b5563" />
      </mesh>
      <mesh position={[cx + 0.85, 1.55, cz]}>
        <boxGeometry args={[0.3, 0.03, 0.03]} />
        <meshStandardMaterial color="#4b5563" />
      </mesh>
    </group>
  );
}

// ---- Rozmieszczenie urządzeń w strefie -------------------------------------

interface DeviceInZoneProps {
  device: DeviceIn3D;
  zone: Zone;
  indexInZone: number;
  totalInZone: number;
  activity: number; // 0-1
}

function DeviceInZone({ device, zone, indexInZone, activity }: DeviceInZoneProps) {
  const anchor = anchorPositionFor(device.type, zone, indexInZone);
  const [x, y, z, rotY] = anchor;

  return (
    <group position={[x, y, z]} rotation={[0, rotY, 0]}>
      <DeviceMesh type={device.type} active={activity} />
      <Text
        position={[0, 2 - y, 0]}
        fontSize={0.13}
        color={activity > 0.05 ? '#fde68a' : '#f8fafc'}
        anchorX="center"
        anchorY="middle"
        outlineWidth={0.008}
        outlineColor="#000000"
      >
        {device.id}
      </Text>
    </group>
  );
}

function anchorPositionFor(type: string, zone: Zone, idx: number): [number, number, number, number] {
  const [cx, cz] = zoneCenter(zone);
  const [x0, z0, x1, z1] = zone.bounds;

  switch (zone.type) {
    case 'KITCHEN':
      if (type === 'REFRIGERATOR') return [x0 + 0.5, 0, z0 + 0.5, 0];
      if (type === 'OVEN') return [cx, 0, z0 + 0.5, 0];
      if (type === 'DISHWASHER') return [x1 - 0.5, 0, z0 + 0.5, 0];
      if (type === 'KETTLE') return [cx - 0.6, 0.95, z0 + 0.5, 0];
      if (type === 'LIGHT') return [cx, 0, cz - 0.5, 0];
      break;
    case 'LIVING_ROOM':
      if (type === 'TV') return [1.05, 0, cz, 0];
      if (type === 'AC') return [1.5, 0, z0 + 0.2, 0];
      if (type === 'HEATER') return [3.5, 0, z0 + 0.15, 0];
      if (type === 'ROUTER') return [1.2, 0.4, 2.5, 0];
      if (type === 'LIGHT') return [cx, 0, cz - 0.3, 0];
      break;
    case 'BEDROOM':
      if (type === 'COMPUTER') return [x0 + 0.8, 0, z0 + 0.35, 0];
      if (type === 'LIGHT') return [cx, 0, cz + 0.3, 0];
      break;
    case 'BATHROOM':
      if (type === 'WASHER') return [x0 + 0.4, 0, cz + 1, 0];
      if (type === 'BOILER') return [x1 - 0.4, 0, cz, -Math.PI / 2];
      if (type === 'LIGHT') return [cx, 0, cz + 0.3, 0];
      break;
    case 'HALLWAY':
      if (type === 'LIGHT') return [cx, 0, cz - 0.3, 0];
      break;
  }
  const w = x1 - x0 - 0.6;
  const d = z1 - z0 - 0.6;
  const perimeter = 2 * (w + d);
  const spacing = perimeter / Math.max(3, 1);
  const offset = ((idx * spacing) % perimeter);
  if (offset < w) return [x0 + 0.3 + offset, 0, z0 + 0.3, Math.PI];
  if (offset < w + d) return [x1 - 0.3, 0, z0 + 0.3 + (offset - w), -Math.PI / 2];
  if (offset < 2 * w + d) return [x1 - 0.3 - (offset - w - d), 0, z1 - 0.3, 0];
  return [x0 + 0.3, 0, z1 - 0.3 - (offset - 2 * w - d), Math.PI / 2];
}

// ---- Meshe urządzeń z aktywnością ------------------------------------------

interface DeviceMeshProps {
  type: string;
  active: number; // 0-1
}

function DeviceMesh({ type, active }: DeviceMeshProps) {
  switch (type) {
    case 'LIGHT': return <LightMesh active={active} />;
    case 'REFRIGERATOR': return <FridgeMesh active={active} />;
    case 'WASHER': return <WasherMesh active={active} />;
    case 'HEATER': return <HeaterMesh active={active} />;
    case 'TV': return <TvMesh active={active} />;
    case 'AC': return <AcMesh active={active} />;
    case 'BOILER': return <BoilerMesh active={active} />;
    case 'OVEN': return <OvenMesh active={active} />;
    case 'DISHWASHER': return <DishwasherMesh active={active} />;
    case 'KETTLE': return <KettleMesh active={active} />;
    case 'COMPUTER': return <ComputerMesh active={active} />;
    case 'ROUTER': return <RouterMesh active={active} />;
    default: return <DefaultMesh />;
  }
}

/** Żarówka - jasność świecenia skaluje się z aktywnością. */
function LightMesh({ active }: { active: number }) {
  const intensity = 0.1 + active * 1.4;
  return (
    <group>
      <mesh position={[0, 1.35, 0]} castShadow>
        <sphereGeometry args={[0.14, 20, 16]} />
        <meshStandardMaterial color="#fef3c7" emissive="#fbbf24" emissiveIntensity={intensity} />
      </mesh>
      <mesh position={[0, 1.42, 0]}>
        <cylinderGeometry args={[0.012, 0.012, 0.1, 8]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
      <pointLight position={[0, 1.35, 0]} intensity={active * 1.2} distance={4} color="#fbbf24" />
    </group>
  );
}

/** Lodówka - pulsujący LED gdy kompresor pracuje. */
function FridgeMesh({ active }: { active: number }) {
  const led = useRef<MeshStandardMaterial>(null);
  useFrame(({ clock }) => {
    if (led.current) {
      const t = clock.getElapsedTime();
      led.current.emissiveIntensity = active > 0.05 ? (Math.sin(t * 3) * 0.5 + 1) * active : 0;
    }
  });
  return (
    <group position={[0, 0.9, 0]}>
      <mesh castShadow receiveShadow>
        <boxGeometry args={[0.7, 1.8, 0.7]} />
        <meshStandardMaterial color="#f5f5f5" metalness={0.3} roughness={0.4} />
      </mesh>
      <mesh position={[-0.35, 0.3, 0.36]} castShadow>
        <boxGeometry args={[0.03, 0.5, 0.05]} />
        <meshStandardMaterial color="#4b5563" metalness={0.8} />
      </mesh>
      <mesh position={[0, 0.5, 0.36]}>
        <boxGeometry args={[0.68, 0.02, 0.01]} />
        <meshStandardMaterial color="#9ca3af" />
      </mesh>
      {/* LED - pulsuje gdy aktywny */}
      <mesh position={[0.25, 0.85, 0.36]}>
        <sphereGeometry args={[0.02, 8, 8]} />
        <meshStandardMaterial ref={led} color="#22c55e" emissive="#22c55e" emissiveIntensity={0} />
      </mesh>
    </group>
  );
}

/** Pralka - obracający się bęben gdy aktywna. */
function WasherMesh({ active }: { active: number }) {
  const drum = useRef<Mesh>(null);
  useFrame((_, delta) => {
    if (drum.current) {
      drum.current.rotation.z += delta * active * 4;
    }
  });
  return (
    <group position={[0, 0.45, 0]}>
      <mesh castShadow receiveShadow>
        <boxGeometry args={[0.6, 0.9, 0.6]} />
        <meshStandardMaterial color="#f3f4f6" metalness={0.2} roughness={0.4} />
      </mesh>
      {/* Bęben - obraca się */}
      <mesh ref={drum} position={[0, 0, 0.31]} castShadow rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.22, 0.22, 0.06, 32]} />
        <meshStandardMaterial color="#374151" metalness={0.8} roughness={0.15} />
      </mesh>
      <mesh position={[0, 0, 0.34]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.16, 0.16, 0.02, 24]} />
        <meshStandardMaterial color="#1f2937" emissive="#3b82f6" emissiveIntensity={active * 0.4} />
      </mesh>
      <mesh position={[0, 0.4, 0.31]}>
        <boxGeometry args={[0.5, 0.08, 0.02]} />
        <meshStandardMaterial color="#e5e7eb" />
      </mesh>
    </group>
  );
}

/** Grzejnik - żeberka świecą na czerwono proporcjonalnie do mocy. */
function HeaterMesh({ active }: { active: number }) {
  const intensity = active * 0.8;
  return (
    <group position={[0, 0.4, 0]}>
      {[-0.4, -0.24, -0.08, 0.08, 0.24, 0.4].map((x, i) => (
        <mesh key={i} position={[x, 0, 0.06]} castShadow>
          <boxGeometry args={[0.08, 0.6, 0.1]} />
          <meshStandardMaterial color="#f9fafb" emissive="#ef4444" emissiveIntensity={intensity} />
        </mesh>
      ))}
      <mesh position={[0, -0.32, 0.06]} castShadow>
        <boxGeometry args={[0.95, 0.05, 0.05]} />
        <meshStandardMaterial color="#f9fafb" />
      </mesh>
      <mesh position={[0, 0.32, 0.06]} castShadow>
        <boxGeometry args={[0.95, 0.05, 0.05]} />
        <meshStandardMaterial color="#f9fafb" />
      </mesh>
      {active > 0.5 && (
        <pointLight position={[0, 0, 0.3]} intensity={active * 0.4} distance={1.5} color="#ef4444" />
      )}
    </group>
  );
}

/** TV - ekran świeci mocniej gdy włączony, kolor pulsuje. */
function TvMesh({ active }: { active: number }) {
  const screen = useRef<MeshStandardMaterial>(null);
  useFrame(({ clock }) => {
    if (screen.current && active > 0.05) {
      const t = clock.getElapsedTime();
      screen.current.emissiveIntensity = 0.3 + active * (0.5 + Math.sin(t * 2) * 0.15);
    } else if (screen.current) {
      screen.current.emissiveIntensity = 0;
    }
  });
  return (
    <group position={[0, 0.95, 0]}>
      <mesh castShadow>
        <boxGeometry args={[0.08, 0.8, 1.35]} />
        <meshStandardMaterial color="#0f172a" />
      </mesh>
      <mesh position={[0.045, 0, 0]}>
        <boxGeometry args={[0.005, 0.72, 1.28]} />
        <meshStandardMaterial ref={screen} color="#1e293b" emissive="#3b82f6" emissiveIntensity={0} />
      </mesh>
      <mesh position={[0, -0.45, 0]} castShadow>
        <boxGeometry args={[0.12, 0.05, 0.35]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
    </group>
  );
}

/** Klimatyzacja - LED aktywności + subtelne świecenie. */
function AcMesh({ active }: { active: number }) {
  return (
    <group position={[0, 1.6, 0]}>
      <mesh castShadow>
        <boxGeometry args={[1.1, 0.3, 0.25]} />
        <meshStandardMaterial color="#ffffff" />
      </mesh>
      <mesh position={[0, -0.15, 0.03]}>
        <boxGeometry args={[1.05, 0.03, 0.2]} />
        <meshStandardMaterial color="#6b7280" emissive="#93c5fd" emissiveIntensity={active * 0.3} />
      </mesh>
      <mesh position={[0.4, -0.05, 0.13]}>
        <sphereGeometry args={[0.015, 8, 8]} />
        <meshStandardMaterial color="#22c55e" emissive="#22c55e" emissiveIntensity={active > 0.1 ? 2.5 : 0.2} />
      </mesh>
    </group>
  );
}

/** Bojler - wyświetlacz świeci gdy pracuje. */
function BoilerMesh({ active }: { active: number }) {
  return (
    <group position={[0, 0.75, 0]}>
      <mesh castShadow receiveShadow>
        <cylinderGeometry args={[0.32, 0.32, 1.3, 20]} />
        <meshStandardMaterial color="#e5e7eb" metalness={0.5} roughness={0.4} />
      </mesh>
      <mesh position={[0, 0.4, 0.33]}>
        <boxGeometry args={[0.2, 0.1, 0.02]} />
        <meshStandardMaterial color="#1e40af" emissive={active > 0.05 ? '#ef4444' : '#3b82f6'} emissiveIntensity={0.5 + active} />
      </mesh>
      <mesh position={[0, 0.15, 0.33]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.05, 0.05, 0.03, 16]} />
        <meshStandardMaterial color="#4b5563" metalness={0.7} />
      </mesh>
    </group>
  );
}

/** Piekarnik - świecąca pomarańczowa szybka + point light gdy grzeje. */
function OvenMesh({ active }: { active: number }) {
  const intensity = active > 0.05 ? 0.8 + active * 1.5 : 0.05;
  return (
    <group position={[0, 0.45, 0]}>
      <mesh castShadow receiveShadow>
        <boxGeometry args={[0.6, 0.9, 0.6]} />
        <meshStandardMaterial color="#1f2937" metalness={0.4} />
      </mesh>
      <mesh position={[0, 0, 0.31]}>
        <boxGeometry args={[0.5, 0.4, 0.02]} />
        <meshStandardMaterial color="#f97316" emissive="#f97316" emissiveIntensity={intensity} metalness={0.5} />
      </mesh>
      <mesh position={[0, 0.25, 0.34]} castShadow>
        <boxGeometry args={[0.5, 0.04, 0.05]} />
        <meshStandardMaterial color="#94a3b8" metalness={0.9} />
      </mesh>
      {[-0.2, -0.07, 0.07, 0.2].map((x, i) => (
        <mesh key={i} position={[x, 0.4, 0.32]} rotation={[Math.PI / 2, 0, 0]}>
          <cylinderGeometry args={[0.035, 0.035, 0.03, 12]} />
          <meshStandardMaterial color="#6b7280" metalness={0.7} />
        </mesh>
      ))}
      {active > 0.3 && (
        <pointLight position={[0, 0, 0.5]} intensity={active * 0.8} distance={1.5} color="#f97316" />
      )}
    </group>
  );
}

/** Zmywarka - LED świeci mocniej gdy pracuje. */
function DishwasherMesh({ active }: { active: number }) {
  return (
    <group position={[0, 0.45, 0]}>
      <mesh castShadow receiveShadow>
        <boxGeometry args={[0.6, 0.9, 0.6]} />
        <meshStandardMaterial color="#d1d5db" metalness={0.6} roughness={0.3} />
      </mesh>
      <mesh position={[0, 0.4, 0.31]}>
        <boxGeometry args={[0.5, 0.08, 0.02]} />
        <meshStandardMaterial color="#374151" emissive="#3b82f6" emissiveIntensity={active * 0.4} />
      </mesh>
      <mesh position={[0, 0.2, 0.34]} castShadow>
        <boxGeometry args={[0.5, 0.04, 0.05]} />
        <meshStandardMaterial color="#4b5563" metalness={0.9} />
      </mesh>
      <mesh position={[0.15, 0.4, 0.32]}>
        <sphereGeometry args={[0.01, 8, 8]} />
        <meshStandardMaterial color="#22c55e" emissive="#22c55e" emissiveIntensity={active > 0.05 ? 3 : 0.3} />
      </mesh>
    </group>
  );
}

/** Czajnik - świeci kolor bursztynowy gdy grzeje. */
function KettleMesh({ active }: { active: number }) {
  return (
    <group position={[0, 0.15, 0]}>
      <mesh position={[0, -0.13, 0]} castShadow>
        <cylinderGeometry args={[0.14, 0.14, 0.03, 16]} />
        <meshStandardMaterial color="#111827" emissive="#f97316" emissiveIntensity={active * 0.5} />
      </mesh>
      <mesh castShadow>
        <cylinderGeometry args={[0.1, 0.13, 0.3, 20]} />
        <meshStandardMaterial color="#f3f4f6" metalness={0.7} roughness={0.2} />
      </mesh>
      <mesh position={[0, 0.17, 0]}>
        <cylinderGeometry args={[0.09, 0.09, 0.04, 16]} />
        <meshStandardMaterial color="#111827" />
      </mesh>
      <mesh position={[-0.14, 0.03, 0]} rotation={[0, 0, Math.PI / 2]}>
        <torusGeometry args={[0.08, 0.018, 8, 20, Math.PI]} />
        <meshStandardMaterial color="#111827" />
      </mesh>
      <mesh position={[0.13, 0.1, 0]} rotation={[0, 0, -Math.PI / 4]} castShadow>
        <coneGeometry args={[0.04, 0.1, 12]} />
        <meshStandardMaterial color="#f3f4f6" metalness={0.7} />
      </mesh>
    </group>
  );
}

/** Komputer - monitor pulsuje niebieskim gdy aktywny. */
function ComputerMesh({ active }: { active: number }) {
  const screen = useRef<MeshStandardMaterial>(null);
  useFrame(({ clock }) => {
    if (screen.current) {
      const t = clock.getElapsedTime();
      screen.current.emissiveIntensity = active > 0.05 ? 0.3 + active * (0.5 + Math.sin(t * 4) * 0.2) : 0.05;
    }
  });
  return (
    <group>
      <mesh position={[0, 0.45, 0]} castShadow receiveShadow>
        <boxGeometry args={[1.3, 0.05, 0.65]} />
        <meshStandardMaterial color="#8b6f47" />
      </mesh>
      {[[-0.6, 0.225, -0.28], [0.6, 0.225, -0.28], [-0.6, 0.225, 0.28], [0.6, 0.225, 0.28]].map((p, i) => (
        <mesh key={i} position={p as [number, number, number]}>
          <boxGeometry args={[0.06, 0.45, 0.06]} />
          <meshStandardMaterial color="#374151" />
        </mesh>
      ))}
      <mesh position={[0, 0.85, -0.25]} castShadow>
        <boxGeometry args={[0.75, 0.45, 0.04]} />
        <meshStandardMaterial color="#0f172a" />
      </mesh>
      <mesh position={[0.02, 0.85, -0.24]}>
        <boxGeometry args={[0.7, 0.4, 0.005]} />
        <meshStandardMaterial ref={screen} color="#1e293b" emissive="#3b82f6" emissiveIntensity={0} />
      </mesh>
      <mesh position={[0, 0.58, -0.2]}>
        <boxGeometry args={[0.18, 0.05, 0.12]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
      <mesh position={[0, 0.49, 0.15]} castShadow>
        <boxGeometry args={[0.5, 0.02, 0.15]} />
        <meshStandardMaterial color="#1f2937" />
      </mesh>
      <mesh position={[0.35, 0.485, 0.15]} castShadow>
        <boxGeometry args={[0.08, 0.03, 0.12]} />
        <meshStandardMaterial color="#374151" />
      </mesh>
    </group>
  );
}

/** Router - dwa mrugające LED gdy aktywny. */
function RouterMesh({ active }: { active: number }) {
  const led1 = useRef<MeshStandardMaterial>(null);
  const led2 = useRef<MeshStandardMaterial>(null);
  useFrame(({ clock }) => {
    const t = clock.getElapsedTime();
    if (led1.current) {
      led1.current.emissiveIntensity = active > 0.05 ? 2 + Math.sin(t * 6) * 1.5 : 0.5;
    }
    if (led2.current) {
      led2.current.emissiveIntensity = active > 0.05 ? 1.5 + Math.sin(t * 8 + 1) * 1 : 0.3;
    }
  });
  return (
    <group position={[0, 0.05, 0]}>
      <mesh castShadow>
        <boxGeometry args={[0.3, 0.06, 0.18]} />
        <meshStandardMaterial color="#111827" />
      </mesh>
      {[-0.1, 0, 0.1].map((x, i) => (
        <mesh key={i} position={[x, 0.15, -0.05]} castShadow>
          <cylinderGeometry args={[0.008, 0.008, 0.2, 8]} />
          <meshStandardMaterial color="#374151" />
        </mesh>
      ))}
      <mesh position={[0.1, 0.035, 0.09]}>
        <sphereGeometry args={[0.01, 8, 8]} />
        <meshStandardMaterial ref={led1} color="#22c55e" emissive="#22c55e" emissiveIntensity={0.5} />
      </mesh>
      <mesh position={[0.13, 0.035, 0.09]}>
        <sphereGeometry args={[0.01, 8, 8]} />
        <meshStandardMaterial ref={led2} color="#3b82f6" emissive="#3b82f6" emissiveIntensity={0.3} />
      </mesh>
    </group>
  );
}

function DefaultMesh() {
  return (
    <mesh position={[0, 0.3, 0]} castShadow>
      <boxGeometry args={[0.4, 0.6, 0.4]} />
      <meshStandardMaterial color="#f472b6" />
    </mesh>
  );
}

/** Dekoracyjny krzew (kula zieleni) - stoi obok mieszkania na trawie. */
function Bush({ position }: { position: [number, number, number] }) {
  return (
    <group position={position}>
      <mesh position={[0, 0.35, 0]} castShadow receiveShadow>
        <sphereGeometry args={[0.5, 12, 10]} />
        <meshStandardMaterial color="#4a7c2f" roughness={0.9} />
      </mesh>
      <mesh position={[0.3, 0.5, 0.2]} castShadow>
        <sphereGeometry args={[0.3, 10, 8]} />
        <meshStandardMaterial color="#3d6a26" roughness={0.9} />
      </mesh>
      <mesh position={[-0.25, 0.45, -0.15]} castShadow>
        <sphereGeometry args={[0.28, 10, 8]} />
        <meshStandardMaterial color="#5a8f38" roughness={0.9} />
      </mesh>
    </group>
  );
}
