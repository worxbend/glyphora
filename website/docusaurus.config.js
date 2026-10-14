// @ts-check
import {themes as prismThemes} from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'glyphora',
  tagline: 'Terminal UI, written like Scala',
  favicon: 'img/favicon.svg',

  url: 'https://worxbend.github.io',
  baseUrl: '/glyphora/',

  organizationName: 'worxbend',
  projectName: 'glyphora',

  // Serve the same brand assets used by README and the generated GitHub Wiki.
  staticDirectories: ['static', '../docs/assets'],

  onBrokenLinks: 'throw',
  onBrokenAnchors: 'throw',
  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  themes: ['@docusaurus/theme-mermaid'],

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/worxbend/glyphora/tree/main/website/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'social-card.svg',
      metadata: [
        {
          name: 'keywords',
          content: 'Scala 3, terminal UI, TUI, reactive widgets, GraalVM native-image',
        },
        {name: 'theme-color', content: '#080b13'},
      ],
      colorMode: {
        defaultMode: 'dark',
        respectPrefersColorScheme: true,
      },
      navbar: {
        title: 'glyphora',
        logo: {
          alt: 'glyphora logo',
          src: 'img/logo.svg',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'docsSidebar',
            position: 'left',
            label: 'Docs',
          },
          {
            href: 'pathname:///api/',
            label: 'API ↗',
            position: 'left',
          },
          {
            href: 'https://github.com/worxbend/glyphora/wiki',
            label: 'Wiki ↗',
            position: 'left',
          },
          {
            href: 'https://github.com/worxbend/glyphora',
            label: 'GitHub ↗',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              {label: 'Getting started', to: '/getting-started'},
              {label: 'Cookbook', to: '/cookbook'},
              {label: 'Widget catalog', to: '/widgets'},
              {label: 'API reference', href: 'pathname:///api/'},
            ],
          },
          {
            title: 'Project',
            items: [
              {label: 'Examples', to: '/examples'},
              {label: 'Architecture', to: '/architecture'},
              {label: 'Contributing', to: '/contributing'},
              {label: 'Versioning', to: '/versioning'},
            ],
          },
          {
            title: 'More',
            items: [
              {label: 'GitHub', href: 'https://github.com/worxbend/glyphora'},
              {label: 'Wiki', href: 'https://github.com/worxbend/glyphora/wiki'},
              {label: 'Releases', href: 'https://github.com/worxbend/glyphora/tags'},
              {label: 'Maven Central', href: 'https://search.maven.org/search?q=g:com.worxbend'},
            ],
          },
        ],
        copyright: `glyphora / Scala 3 terminal UI / © ${new Date().getFullYear()} contributors / MIT`,
      },
      prism: {
        theme: prismThemes.oneLight,
        darkTheme: prismThemes.oneDark,
        additionalLanguages: ['java', 'scala', 'bash', 'json'],
      },
    }),
};

export default config;
