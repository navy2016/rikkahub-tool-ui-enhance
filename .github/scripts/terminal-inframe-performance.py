"""Independent frozen in-frame publication performance: refresh gain plus sync/return guards."""
import collections,hashlib,importlib.util,json,statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PROTOCOL=ROOT/'benchmarks/terminal-inframe-performance/protocol.json'
PROTOCOL_SHA256='92a30fc4b284b046fd310bf28170fdf2fe1c5280b600cbf0efa311f67b811c0e'
s=importlib.util.spec_from_file_location('inframe_perf_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
require=identity.require
METRICS=('inputToBufferEvidenceNs','inputToAttemptNs','inputToDisplayEndNs')
def protocol():
    raw=PROTOCOL.read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA256,'Frozen in-frame performance protocol changed')
    return json.loads(raw)
def complete_window(row):
    return row['identityDecidable'] and not row['noSubmittedAttempt'] and not row['noPresentedAttempt'] and all(
        type(row.get(key)) is int and row[key]>0 for key in METRICS)
def aggregate(invocations):
    p=protocol();matrix=p['matrix'];gates=p['gates']
    expected=[(n,pos,arm) for n,order in enumerate(matrix['armOrder']) for pos,arm in enumerate(order)]
    require([(x['pair'],x['position'],x['arm']) for x in invocations]==expected,'Four complete ordered launch pairs required')
    rows=[]
    for inv in invocations:
        groups=inv['result']['classifications'];require([x['case'] for x in groups]==matrix['cases'],'All four ordered cases required')
        for group in groups:
            expected=[(n,pos,cond) for n,order in enumerate(matrix['conditionOrder']) for pos,cond in enumerate(order)]
            require([(x['index'],x['position'],x['condition']) for x in group['rows']]==expected,'Per-case three-condition inventory changed')
            require(all(r['warmup'] is (r['index'] in matrix['warmupRounds']) for r in group['rows']),'Warmup labels changed')
            rows.extend(group['rows'])
    require(len(rows)==matrix['windows'],'Fixed480window matrix incomplete')
    summary=[]
    for case in matrix['cases']:
        for condition in matrix['conditions']:
            item=dict(case=case,condition=condition,pairs=len(matrix['armOrder']))
            for key in METRICS:
                arms={arm:[] for arm in ('legacy','candidate')}
                for pair in range(len(matrix['armOrder'])):
                    for arm in arms:
                        result=next(x['result'] for x in invocations if x['pair']==pair and x['arm']==arm)
                        group=next(g for g in result['classifications'] if g['case']==case)
                        values=[r.get(key) for r in group['rows'] if r['condition']==condition and not r['warmup']]
                        require(len(values)==matrix['formalPerConditionPerCasePerArmPerPair'],'Four formal samples required')
                        arms[arm].append(statistics.median(values) if all(type(x) is int and x>0 for x in values) else None)
                ratios=[b/a if a is not None and b is not None else None for a,b in zip(arms['legacy'],arms['candidate'])]
                complete=all(r is not None for r in ratios)
                item[key]=dict(arms=arms,pairedRatios=ratios,medianRatio=statistics.median(ratios) if complete else None,
                    complete=complete,wins=sum(r<1 for r in ratios if r is not None))
            summary.append(item)
    data_complete=all(complete_window(r) for r in rows);failures=[]
    if not data_complete:failures.append('IDENTITY_OR_ENDPOINT_INCOMPLETE')
    for item in summary:
        label=item['case']+'/'+item['condition']
        for key,refresh,other,wins in (
            ('inputToBufferEvidenceNs',gates['refreshPrimaryRatioMax'],gates['syncAndReturnPrimaryRatioMax'],gates['refreshPrimaryWinningPairsMin']),
            ('inputToAttemptNs',gates['refreshUiRatioMax'],gates['syncAndReturnUiRatioMax'],gates['refreshUiWinningPairsMin']),
            ('inputToDisplayEndNs',gates['systemRatioMaxAllConditions'],gates['systemRatioMaxAllConditions'],0)):
            value=item[key];limit=refresh if item['condition']=='refresh' else other
            if not value['complete']:failures.append(label+':'+key+':MISSING')
            elif value['medianRatio']>limit or (item['condition']=='refresh' and value['wins']<wins):failures.append(label+':'+key+':GATE')
    return dict(summary=summary,windows=len(rows),warmups=sum(r['warmup'] for r in rows),dataComplete=data_complete,
        passesFrozenPerformanceScreen=not failures,gateFailures=failures,productionAdopted=False)
