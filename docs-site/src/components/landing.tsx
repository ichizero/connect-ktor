import Link from "fumadocs-core/link";
import {
  ArrowRightIcon,
  BlocksIcon,
  GitBranchIcon,
  LayersIcon,
  ShieldAlertIcon,
  SparklesIcon,
  WorkflowIcon,
} from "lucide-react";

const features = [
  {
    title: "REST coexistence",
    description:
      "Register Connect handlers next to ordinary Ktor routes. Keep REST until each endpoint earns a Connect replacement.",
    href: "/introduction",
    icon: LayersIcon,
  },
  {
    title: "Connect protocol, intact",
    description:
      "JSON or binary codecs, Connect GET, structured errors, and client-streaming with envelope framing stay on the server.",
    href: "/conformance",
    icon: WorkflowIcon,
  },
  {
    title: "React + Kotlin/Ktor",
    description:
      "Connect-ES in front, Connect-Ktor in back, one .proto module. A natural split when the stack is already typed that way.",
    href: "/introduction",
    icon: BlocksIcon,
  },
] as const;

const startHere = [
  {
    title: "Introduction",
    description: "What it is, who it is for, and how it relates to Connect-Kotlin.",
    href: "/introduction",
  },
  {
    title: "Getting started",
    description: "Dependencies, code generation, and a handler beside REST.",
    href: "/getting-started",
  },
  {
    title: "Plugins",
    description: "Serialization, Connect GET, limits, compression, protovalidate.",
    href: "/plugins",
  },
  {
    title: "Known limitations",
    description: "gRPC, server/bidi streaming, and engine gaps — stated up front.",
    href: "/known-limitations",
  },
] as const;

export function LandingPage() {
  return (
    <div className="relative flex flex-col gap-20 pb-16 pt-6 md:gap-28 md:pb-24 md:pt-10">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 -top-24 h-[32rem] bg-[radial-gradient(ellipse_at_top,var(--color-brand-glow),transparent_60%)]"
      />

      <section className="relative flex flex-col items-center text-center">
        <p className="mb-4 inline-flex items-center gap-2 rounded-full border border-brand-cyan/30 bg-brand-cyan/10 px-3 py-1 text-sm font-medium text-brand-blue">
          <SparklesIcon className="size-3.5" />
          Connect Protocol for Ktor servers
        </p>

        <h1 className="max-w-4xl text-4xl font-semibold tracking-tight text-fd-foreground sm:text-5xl lg:text-6xl">
          Add Connect beside REST.
          <br />
          <span className="bg-gradient-to-r from-brand-blue to-brand-cyan bg-clip-text text-transparent">
            Migrate in small cuts.
          </span>
        </h1>

        <p className="mt-6 max-w-2xl text-base text-fd-muted-foreground sm:text-lg">
          Connect-Ktor brings the Connect Protocol to existing Ktor apps without
          asking them to abandon REST. Same process, typed RPCs, gradual adoption.
        </p>

        <div className="mt-8 flex flex-col items-center gap-3 sm:flex-row">
          <Link
            href="/getting-started"
            className="inline-flex items-center justify-center gap-2 rounded-full bg-brand-blue px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-brand-blue-hover"
          >
            Getting Started
            <ArrowRightIcon className="size-4" />
          </Link>
          <Link
            href="/introduction"
            className="inline-flex items-center justify-center gap-2 rounded-full border border-fd-border bg-fd-secondary px-6 py-3 text-sm font-medium text-fd-secondary-foreground transition-colors hover:bg-fd-accent"
          >
            Read Introduction
          </Link>
        </div>

        <div className="mt-8 flex max-w-2xl items-start gap-3 rounded-2xl border border-brand-blue/15 bg-brand-blue/[0.04] px-4 py-3 text-left text-sm text-fd-muted-foreground">
          <ShieldAlertIcon className="mt-0.5 size-4 shrink-0 text-brand-blue" />
          <p>
            <span className="font-medium text-fd-foreground">Unofficial library.</span>{" "}
            Connect-Ktor is a community project, not published by the ConnectRPC
            organization. It extends Connect-Kotlin for Ktor and tracks the
            Connect conformance suite independently. For Connect itself,
            Connect-Kotlin clients, or the protocol, see the official{" "}
            <a
              href="https://connectrpc.com/"
              className="font-medium text-brand-blue underline-offset-4 hover:underline"
            >
              connectrpc.com
            </a>{" "}
            docs.
          </p>
        </div>
      </section>

      <section className="relative">
        <div className="mb-8 max-w-2xl">
          <p className="mb-2 text-sm font-medium text-brand-cyan">Why teams use it</p>
          <h2 className="text-3xl font-semibold tracking-tight lg:text-4xl">
            Production Ktor, without a rewrite.
          </h2>
          <p className="mt-3 text-fd-muted-foreground">
            Keep the server you have. Layer Connect where it pays off, and leave
            the rest of the REST surface alone.
          </p>
        </div>

        <div className="grid gap-4 md:grid-cols-3">
          {features.map((feature) => (
            <Link
              key={feature.title}
              href={feature.href}
              className="group rounded-2xl border bg-fd-card p-6 shadow-sm transition-colors hover:border-brand-cyan/40 hover:bg-brand-cyan/[0.04]"
            >
              <div className="mb-4 w-fit rounded-xl border bg-brand-blue/5 p-2 text-brand-blue">
                <feature.icon className="size-5" />
              </div>
              <h3 className="mb-2 text-base font-semibold tracking-tight">
                {feature.title}
              </h3>
              <p className="text-sm text-fd-muted-foreground">
                {feature.description}
              </p>
              <span className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-brand-blue opacity-0 transition-opacity group-hover:opacity-100">
                Learn more
                <ArrowRightIcon className="size-3.5" />
              </span>
            </Link>
          ))}
        </div>
      </section>

      <section className="relative">
        <div className="mb-8 flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
          <div className="max-w-2xl">
            <p className="mb-2 text-sm font-medium text-brand-cyan">Documentation</p>
            <h2 className="text-3xl font-semibold tracking-tight lg:text-4xl">
              Start where you need.
            </h2>
          </div>
          <Link
            href="https://github.com/ichizero/connect-ktor"
            className="inline-flex items-center gap-2 text-sm font-medium text-brand-blue hover:underline"
          >
            <GitBranchIcon className="size-4" />
            View on GitHub
          </Link>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          {startHere.map((item) => (
            <Link
              key={item.title}
              href={item.href}
              className="rounded-2xl border bg-fd-card px-5 py-4 transition-colors hover:border-brand-blue/35 hover:bg-brand-blue/[0.03]"
            >
              <h3 className="font-semibold tracking-tight">{item.title}</h3>
              <p className="mt-1 text-sm text-fd-muted-foreground">
                {item.description}
              </p>
            </Link>
          ))}
        </div>
      </section>

      <section className="relative overflow-hidden rounded-3xl border border-brand-blue/20 bg-gradient-to-br from-brand-blue to-brand-cyan px-6 py-10 text-white md:px-10 md:py-12">
        <div
          aria-hidden
          className="pointer-events-none absolute -right-16 -top-16 size-56 rounded-full bg-white/10 blur-2xl"
        />
        <div className="relative max-w-2xl">
          <h2 className="text-3xl font-semibold tracking-tight">
            Ship Connect on the Ktor you already run.
          </h2>
          <p className="mt-3 text-white/85">
            Wire a handler, keep your REST routes, and grow the Connect surface
            one RPC at a time.
          </p>
          <div className="mt-6 flex flex-col gap-3 sm:flex-row">
            <Link
              href="/getting-started"
              className="inline-flex items-center justify-center gap-2 rounded-full bg-white px-6 py-3 text-sm font-medium text-brand-blue transition-colors hover:bg-white/90"
            >
              Getting Started
              <ArrowRightIcon className="size-4" />
            </Link>
            <Link
              href="/conformance"
              className="inline-flex items-center justify-center gap-2 rounded-full border border-white/40 px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-white/10"
            >
              Conformance matrix
            </Link>
          </div>
        </div>
      </section>
    </div>
  );
}
