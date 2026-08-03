/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          50:  '#eff6ff',
          100: '#dbeafe',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
        },
      },
      gridTemplateColumns: {
        // Do "Wzorzec dnia" - 24 godziny doby w rzedzie
        '24': 'repeat(24, minmax(0, 1fr))',
      },
    },
  },
  plugins: [],
};
