import clsx from 'clsx';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import CodeBlock from '@theme/CodeBlock';
import Heading from '@theme/Heading';
import useBaseUrl from '@docusaurus/useBaseUrl';

import styles from './index.module.css';

const quickStart = `import io.worxbend.tui.dsl.*

class CounterApp extends TuiApp:
  private val count = Signal(0)

  override def bindings: KeyBindings = KeyBindings(
    binding("+", "increment")(count.update(_ + 1)),
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    scaffold(statusBar = Some(statusBar(bindings))) {
      centered(34, 7) {
        panel("Counter")(
          text(s"count: \${count.get}").bold.fg(Color.Cyan),
          text("press + to increment").dim,
        ).rounded
      }
    }

object Counter extends CounterApp`;

const proof = [
  ['40+', 'widgets'],
  ['1', 'DSL import'],
  ['0', 'reflection config'],
  ['100%', 'headless-testable'],
];

const features = [
  {
    icon: 'signals',
    emoji: '⚡',
    kicker: 'REACTIVE CORE',
    title: 'State that redraws only what reads it.',
    body: 'Signal and Computed track dependencies while your view runs. Change a value; glyphora invalidates the right work without reducers, dispatch loops, or dependency arrays.',
    meta: ['Signal[A]', 'Computed[A]', 'ReactiveScope'],
    size: 'featureWide',
  },
  {
    icon: 'widgets',
    emoji: '🧩',
    kicker: 'WIDGET ATLAS',
    title: 'A serious UI kit for a text grid.',
    body: 'Compose inputs, tables, markdown, trees, charts, forms, loading states, and rich layout primitives without leaving typed Scala.',
    meta: ['40+ widgets', 'braille charts', 'cluster-safe input'],
    size: 'featureWide',
  },
  {
    icon: 'chrome',
    emoji: '⌘',
    kicker: 'APP CHROME',
    title: 'The shell is already wired.',
    body: 'Top bar, sidebar, status hints, themes, screen stacks, toasts, and a fuzzy command palette share one key registry.',
    meta: ['Ctrl+P', 'screens', 'toasts'],
    size: 'featureThird',
  },
  {
    icon: 'effects',
    emoji: '✦',
    kicker: 'MOTION',
    title: 'Effects after render.',
    body: 'Fade, sweep, coalesce, dissolve, pulse, and typewriter transforms compose with easing—without contaminating widget logic.',
    meta: ['sequence', 'parallel', 'Easing'],
    size: 'featureThird',
  },
  {
    icon: 'native',
    emoji: '📦',
    kicker: 'NATIVE FIRST',
    title: 'Ship one fast binary.',
    body: 'GraalVM native-image works with --no-fallback and no reflect-config. Compile-time Scala 3 derivation keeps runtime honest.',
    meta: ['GraalVM', 'zero reflect', 'fast start'],
    size: 'featureThird',
  },
  {
    icon: 'unicode',
    emoji: '🌍',
    kicker: 'TERMINAL TRUTH',
    title: 'Emoji, CJK, flags, and combining marks line up.',
    body: 'Unicode display width comes from generated UCD tables. Grapheme-aware measuring, wrapping, clipping, and editing are foundational—not patches.',
    meta: ['ZWJ families', 'CJK', 'graphemes'],
    size: 'featureWide',
  },
  {
    icon: 'testing',
    emoji: '🧪',
    kicker: 'HEADLESS BY DESIGN',
    title: 'Test the app, not screenshots of the app.',
    body: 'Run complete input and render cycles against HeadlessBackend. Pilot types, clicks, resizes, waits, and exposes the terminal exactly as a user sees it.',
    meta: ['Pilot', 'BufferAssertions', 'CI friendly'],
    size: 'featureWide',
  },
];

const path = [
  {
    number: '01',
    title: 'Model',
    body: 'Put mutable values in Signal. Derive everything else with Computed.',
    code: 'val filter = Signal("")',
  },
  {
    number: '02',
    title: 'Compose',
    body: 'Return an Element tree from view. Layout and style stay typed and local.',
    code: 'panel("Jobs")(dataTable(state))',
  },
  {
    number: '03',
    title: 'Ship',
    body: 'Run on the JVM, test headlessly, or compile a zero-reflection native binary.',
    code: './mill app.nativeImage',
  },
];

function Hero() {
  const banner = useBaseUrl('banner.svg');
  return (
    <header className={styles.hero}>
      <div className={styles.heroGlow} aria-hidden="true" />
      <div className={clsx('container', styles.heroGrid)}>
        <div className={styles.heroCopy}>
          <div className={styles.eyebrow}>
            <span className={styles.liveDot} />
            SCALA 3 / TERMINAL UI
          </div>
          <Heading as="h1">
            Terminal UI,
            <span>written like Scala.</span>
          </Heading>
          <p className={styles.heroLead}>
            A reactive, batteries-included toolkit for terminal apps that feel designed—not assembled.
            Typed views, real widgets, expressive motion, and native binaries from one coherent stack.
          </p>
          <div className={styles.heroActions}>
            <Link className={styles.primaryButton} to="/getting-started">
              Build your first screen <span aria-hidden="true">→</span>
            </Link>
            <Link className={styles.ghostButton} to="/examples">
              See complete apps <span aria-hidden="true">↗</span>
            </Link>
          </div>
          <div className={styles.installLine} aria-label="Mill dependency">
            <span>$</span>
            <code>mvn&quot;com.worxbend::tui-dsl:0.21.0&quot;</code>
            <span className={styles.cursor} aria-hidden="true" />
          </div>
        </div>

        <div className={styles.heroMedia}>
          <div className={styles.mediaLabel}><span>01</span> PRODUCT SIGNAL</div>
          <div className={styles.bannerFrame}>
            <div className={styles.frameChrome} aria-hidden="true">
              <i /><i /><i />
              <span>docs / glyphora.scala</span>
            </div>
            <img src={banner} alt="glyphora Scala terminal UI toolkit banner" />
          </div>
          <div className={clsx(styles.floatTag, styles.tagTop)}>⚡ reactive</div>
          <div className={clsx(styles.floatTag, styles.tagBottom)}>✓ native-image</div>
        </div>
      </div>

      <div className={clsx('container', styles.proofRail)}>
        {proof.map(([value, label]) => (
          <div className={styles.proofItem} key={label}>
            <strong>{value}</strong>
            <span>{label}</span>
          </div>
        ))}
        <Link to="/architecture" className={styles.proofLink}>inspect the stack →</Link>
      </div>
    </header>
  );
}

function FeatureCard({feature, index}) {
  const icon = useBaseUrl(`icons/${feature.icon}.svg`);
  return (
    <article className={clsx(styles.featureCard, styles[feature.size])}>
      <div className={styles.featureHeader}>
        <div className={styles.iconFrame}>
          <img src={icon} alt="" />
        </div>
        <span className={styles.featureIndex}>0{index + 1}</span>
      </div>
      <div className={styles.featureKicker}>{feature.emoji} {feature.kicker}</div>
      <Heading as="h3">{feature.title}</Heading>
      <p>{feature.body}</p>
      <div className={styles.metaRow}>
        {feature.meta.map((item) => <code key={item}>{item}</code>)}
      </div>
    </article>
  );
}

function Capabilities() {
  return (
    <section className={styles.capabilities}>
      <div className="container">
        <div className={styles.sectionIntro}>
          <div>
            <span className={styles.sectionNumber}>02 / CAPABILITIES</span>
            <Heading as="h2">Less framework ceremony.<br />More terminal craft.</Heading>
          </div>
          <p>
            Each layer is useful alone. Together they cover the path from state mutation to the final ANSI diff—without reflection or a browser-shaped architecture.
          </p>
        </div>
        <div className={styles.featureGrid}>
          {features.map((feature, index) => (
            <FeatureCard feature={feature} index={index} key={feature.title} />
          ))}
        </div>
      </div>
    </section>
  );
}

function Architecture() {
  const nodes = [
    ['Signal', 'tracked state', 'signal'],
    ['Element', 'typed view tree', 'element'],
    ['Widget', 'buffer render', 'widget'],
    ['Diff', 'changed cells only', 'diff'],
    ['ANSI', 'your terminal', 'ansi'],
  ];
  return (
    <section className={styles.architecture}>
      <div className="container">
        <div className={styles.archShell}>
          <div className={styles.archCopy}>
            <span className={styles.sectionNumber}>03 / ONE PIPELINE</span>
            <Heading as="h2">From a signal to a terminal cell.</Heading>
            <p>
              The runtime records signal reads, rebuilds the element tree, renders widgets into a headless buffer, and flushes only changed cells. The exact pipeline powers production and tests.
            </p>
            <Link to="/architecture">Walk through the architecture →</Link>
          </div>
          <div className={styles.pipeline} aria-label="glyphora render pipeline">
            {nodes.map(([name, label, tone], index) => (
              <div className={styles.pipelineGroup} key={name}>
                <div className={clsx(styles.pipelineNode, styles[tone])}>
                  <span>{String(index + 1).padStart(2, '0')}</span>
                  <strong>{name}</strong>
                  <small>{label}</small>
                </div>
                {index < nodes.length - 1 && <i aria-hidden="true">→</i>}
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

function LearningPath() {
  return (
    <section className={styles.learningPath}>
      <div className="container">
        <div className={styles.sectionIntro}>
          <div>
            <span className={styles.sectionNumber}>04 / YOUR FIRST 10 MINUTES</span>
            <Heading as="h2">Model. Compose. Ship.</Heading>
          </div>
          <p>Three ideas carry from a hello-world counter to dashboards, forms, file pickers, and full-screen tools.</p>
        </div>
        <div className={styles.pathGrid}>
          {path.map((step) => (
            <article className={styles.pathCard} key={step.number}>
              <span>{step.number}</span>
              <Heading as="h3">{step.title}</Heading>
              <p>{step.body}</p>
              <code>{step.code}</code>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function QuickStart() {
  return (
    <section className={styles.quickStart}>
      <div className="container">
        <div className={styles.quickShell}>
          <div className={styles.quickCopy}>
            <span className={styles.sectionNumber}>05 / START HERE</span>
            <Heading as="h2">A complete reactive app in one file.</Heading>
            <p>
              Add one dependency, declare the keys, and return an element tree. The status bar is generated from the same bindings that dispatch commands.
            </p>
            <ul>
              <li><span>✓</span> no plugin or code generator</li>
              <li><span>✓</span> no UI thread ceremony</li>
              <li><span>✓</span> no runtime reflection</li>
            </ul>
            <div className={styles.quickLinks}>
              <Link to="/getting-started">Follow the guided setup →</Link>
              <Link to="/cookbook">Browse practical recipes →</Link>
            </div>
          </div>
          <div className={styles.codePanel}>
            <div className={styles.codeMeta}>
              <span>Counter.scala</span>
              <span>Scala 3</span>
            </div>
            <CodeBlock language="scala">{quickStart}</CodeBlock>
          </div>
        </div>
      </div>
    </section>
  );
}

function FinalCallout() {
  return (
    <section className={styles.finalCallout}>
      <div className={clsx('container', styles.finalShell)}>
        <div>
          <span className={styles.sectionNumber}>READY WHEN YOUR TERMINAL IS</span>
          <Heading as="h2">Build something<br />glyphorious.</Heading>
        </div>
        <div className={styles.finalActions}>
          <Link className={styles.primaryButton} to="/getting-started">Read the guide →</Link>
          <a className={styles.ghostButton} href="https://github.com/worxbend/glyphora">Star on GitHub ↗</a>
        </div>
      </div>
    </section>
  );
}

export default function Home() {
  return (
    <Layout
      title="Scala 3 terminal UI toolkit"
      description="Build reactive, expressive terminal interfaces in Scala 3 with rich widgets, motion, mouse support, headless testing, and GraalVM native-image support.">
      <main className={styles.home}>
        <Hero />
        <Capabilities />
        <Architecture />
        <LearningPath />
        <QuickStart />
        <FinalCallout />
      </main>
    </Layout>
  );
}
