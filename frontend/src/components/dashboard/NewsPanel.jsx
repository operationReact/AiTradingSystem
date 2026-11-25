import { Sparkles } from 'lucide-react';
import { DataState } from '../DataState.jsx';
import { Badge, Button, EmptyState, Panel } from '../ui.jsx';
import { formatDateTime } from '../../lib/format.js';

const SENTIMENT_TONE = { POSITIVE: 'gain', NEGATIVE: 'loss', NEUTRAL: 'neutral', MIXED: 'warn' };

function ProviderLine({ status }) {
  if (!status.configured) {
    return (
      <p className="text-xs text-muted">
        AI analysis is unconfigured ({status.provider === 'NONE' ? 'no provider selected' : `${status.provider} key missing`}).
        Articles are shown without analysis.
      </p>
    );
  }
  return (
    <p className="text-xs text-muted">
      Provider {status.provider} · model {status.model}
      {status.lastSuccessfulAnalysisAt && <> · last analysis {formatDateTime(status.lastSuccessfulAnalysisAt)}</>}
      {status.lastErrorCode && <> · last error {status.lastErrorCode}</>}
    </p>
  );
}

function ArticleRow({ article, configured, onAnalyze, pending }) {
  const a = article.analysis;
  return (
    <li className="flex flex-col gap-1.5 border-t border-border py-3 first:border-t-0 first:pt-0">
      <div className="flex items-start justify-between gap-3">
        <a
          href={article.url}
          target="_blank"
          rel="noopener noreferrer"
          className="text-sm font-medium leading-snug text-text hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
        >
          {article.title}
        </a>
        <span className="shrink-0 text-xs text-muted">{formatDateTime(article.publishedAt)}</span>
      </div>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
        <span>{article.source}</span>
        {a?.status === 'ANALYZED' && (
          <>
            <Badge tone={SENTIMENT_TONE[a.sentiment] ?? 'neutral'}>{a.sentiment}</Badge>
            <span>{a.category}</span>
            <span>conf. {Number(a.confidence).toFixed(2)}</span>
            {a.impactedSymbols.map((s) => (
              <Badge key={s} tone="accent">{s}</Badge>
            ))}
          </>
        )}
        {a?.status === 'FAILED' && <Badge tone="loss">Analysis failed: {a.errorCode}</Badge>}
        <Button
          size="sm"
          variant="ghost"
          icon={Sparkles}
          disabled={!configured}
          loading={pending}
          title={configured ? 'Run AI analysis for this article' : 'Configure an AI provider to analyse articles'}
          onClick={() => onAnalyze(article.id)}
          className="ml-auto"
        >
          {a?.status === 'ANALYZED' ? 'Re-analyse' : 'Analyse'}
        </Button>
      </div>
    </li>
  );
}

export function NewsPanel({ controller }) {
  const { status, articles, analyze, connected, onAnalyze } = controller;

  return (
    <Panel title="News intelligence" subtitle="Ingested articles and advisory AI analysis">
      <DataState query={status}>
        {(s) => (
          <div className="flex flex-col gap-3">
            <ProviderLine status={s} />
            {analyze.isError && (
              <p role="alert" className="text-xs text-loss">
                Analysis did not complete: {analyze.error?.message}
              </p>
            )}
            {connected && (
              <DataState query={articles} skeleton={null}>
                {(page) =>
                  page.items.length === 0 ? (
                    <EmptyState
                      title="No news articles ingested"
                      description="Articles appear after a configured source supplies them."
                    />
                  ) : (
                    <ul className="flex flex-col">
                      {page.items.map((article) => (
                        <ArticleRow
                          key={article.id}
                          article={article}
                          configured={s.configured}
                          pending={analyze.isPending && analyze.variables === article.id}
                          onAnalyze={onAnalyze}
                        />
                      ))}
                    </ul>
                  )
                }
              </DataState>
            )}
          </div>
        )}
      </DataState>
    </Panel>
  );
}
