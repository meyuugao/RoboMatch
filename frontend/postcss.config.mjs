/**
 * PostCSS-конфигурация для Tailwind CSS 4.
 * В 4-й версии плагин один: @tailwindcss/postcss (конфиг tailwind.config
 * больше не нужен — стили через @import "tailwindcss" в globals.css).
 */
const config = {
  plugins: {
    "@tailwindcss/postcss": {},
  },
};

export default config;
