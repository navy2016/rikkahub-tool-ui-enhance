"""Preregistered P2 exact-buffer performance, independent of old failed P2 matrices."""
import collections,hashlib,importlib.util,json,statistics
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
PROTOCOL=ROOT/'benchmarks/terminal-p2-identity-performance/protocol.json'
PROTOCOL_SHA256='99a3281c6646b4cbe22964d293774d0f8cbb12091a2a1bc0558109f925b4368f'
s=importlib.util.spec_from_file_location('p2_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
require=identity.require
METRICS=('inputToBufferEvidenceNs','inputToAttemptNs','inputToDisplayEndNs')

def protocol():
    raw=PROTOCOL.read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA256,'Frozen P2 performance protocol changed')
    return json.loads(raw)

def complete_window(row):
    return row['identityDecidable'] and not row['noSubmittedAttempt'] and not row['noPresentedAttempt'] and all(
        isinstance(row.get(key),int) and not isinstance(row.get(key),bool) and row[key]>0 for key in METRICS)

def aggregate(invocations):
    """No deletion/imputation of missing values and no switching the primary after seeing results."""
    p=protocol();matrix=p['matrix'];gates=p['gates']
    expected=[(n,pos,arm) for n,order in enumerate(matrix['armOrder']) for pos,arm in enumerate(order)]
    require([(i['pair'],i['position'],i['arm']) for i in invocations]==expected,'Four complete launch pairs required')
    all_rows=[]
    for invocation in invocations:
        results=invocation['result']['classifications'];require([r['case'] for r in results]==matrix['cases'],'Four ordered case results required')
        for result in results:
            expected_windows=[(r,pos,cond) for r in range(matrix['rounds']) for pos,cond in enumerate(('refresh','return') if r%2==0 else ('return','refresh'))]
            require([(w['index'],w['position'],w['condition']) for w in result['rows']]==expected_windows,'Per-case fixed window inventory changed')
            require(all(w['warmup'] is (w['index'] in matrix['warmupRounds']) for w in result['rows']),'Warmup classification changed')
            all_rows.extend(result['rows'])
    require(len(all_rows)==matrix['windows'],'Full320window inventory required')
    summary=[]
    for case in matrix['cases']:
        for condition in matrix['conditions']:
            entry=dict(case=case,condition=condition,pairs=matrix['pairs'])
            for key in METRICS:
                arms={a:[] for a in ('legacy','candidate')}
                for n in range(matrix['pairs']):
                    for arm in arms:
                        result=next(i['result'] for i in invocations if i['pair']==n and i['arm']==arm)
                        group=next(r for r in result['classifications'] if r['case']==case)
                        selected=[r for r in group['rows'] if r['condition']==condition and not r['warmup']]
                        require(len(selected)==matrix['samplesPerConditionPerCasePerArmPerPair'],'Four formal windows required')
                        values=[r.get(key) for r in selected]
                        arms[arm].append(statistics.median(values) if all(isinstance(v,int) and not isinstance(v,bool) and v>0 for v in values) else None)
                ratios=[b/a if a is not None and b is not None else None for a,b in zip(arms['legacy'],arms['candidate'])]
                complete=all(r is not None for r in ratios)
                entry[key]=dict(arms=arms,pairedRatios=ratios,medianRatio=statistics.median(ratios) if complete else None,
                    wins=sum(r<1 for r in ratios if r is not None),complete=complete)
            summary.append(entry)
    data_complete=all(complete_window(r) for r in all_rows)
    failures=[]
    if not data_complete:failures.append('MATRIX_IDENTITY_OR_ENDPOINT_INCOMPLETE')
    for row in summary:
        label=row['case']+'/'+row['condition']
        for key,return_limit,refresh_limit,wins in (
            ('inputToBufferEvidenceNs',gates['returnPrimaryRatioMax'],gates['refreshPrimaryRatioMax'],gates['returnPrimaryWinningPairsMin']),
            ('inputToAttemptNs',gates['returnUiRatioMax'],gates['refreshUiRatioMax'],gates['returnUiWinningPairsMin']),
            ('inputToDisplayEndNs',gates['systemRatioMaxBothConditions'],gates['systemRatioMaxBothConditions'],0)):
            metric=row[key];limit=return_limit if row['condition']=='return' else refresh_limit
            if not metric['complete']:failures.append(label+':'+key+':MISSING')
            elif metric['medianRatio']>limit or (row['condition']=='return' and metric['wins']<wins):failures.append(label+':'+key+':GATE')
    return dict(summary=summary,windows=len(all_rows),warmups=sum(r['warmup'] for r in all_rows),dataComplete=data_complete,
        passesFrozenPerformanceScreen=not failures,gateFailures=failures,productionAdopted=False)
