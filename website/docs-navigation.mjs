/**
 * Canonical documentation navigation.
 *
 * Docusaurus consumes this directly and scripts/export-wiki.mjs uses the same
 * structure to build the GitHub Wiki sidebar. Keep page IDs aligned with files
 * in website/docs.
 */
export const docsNavigation = [
  {
    type: 'category',
    label: 'Start here',
    collapsed: false,
    items: ['intro', 'getting-started', 'examples'],
  },
  {
    type: 'category',
    label: 'Fundamentals',
    collapsed: false,
    items: [
      'state-and-signals',
      'component-state',
      'layout-and-style',
      'responsive',
      'widgets',
      'editing',
      'mouse',
      'unicode-and-accessibility',
      'architecture',
    ],
  },
  {
    type: 'category',
    label: 'Build applications',
    collapsed: false,
    items: [
      'app-shell',
      'keybindings',
      'forms-and-validation',
      'async-and-timers',
      'motion',
      'cookbook',
      'print-mode',
    ],
  },
  {
    // Task-shaped pages: each owns one problem an application runs into, and the
    // tutorials below link into them rather than restating the reasoning.
    type: 'category',
    label: 'Recipes',
    collapsed: false,
    items: [
      'live-data',
      'tables-and-selection',
      'charts-and-status',
    ],
  },
  {
    // Start-to-finish builds. Each produces a real app that also ships in examples/,
    // so every snippet in them is code that compiles and is tested.
    type: 'category',
    label: 'Build a real app',
    collapsed: false,
    items: [
      'build-a-process-monitor',
      'build-a-sensor-dashboard',
      'build-a-load-generator',
    ],
  },
  {
    type: 'category',
    label: 'Ship with confidence',
    collapsed: false,
    items: ['testing', 'performance', 'native-image', 'troubleshooting', 'faq'],
  },
  {
    type: 'category',
    label: 'Project',
    collapsed: false,
    items: ['contributing', 'versioning'],
  },
];

export default docsNavigation;
