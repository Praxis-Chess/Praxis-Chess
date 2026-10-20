"""
The sample games as SQL for the sample database (POLISH_PLAN.md U7).

Rows exactly as a Chess.com sync writes them (SyncService.toGame): source
CHESS_COM, PENDING, the PGN as played. Run against praxis_sample only, never
the player's own database:

    python scripts/sample/load_sql.py games.json > games.sql
    docker exec -i praxis-chess-postgres psql -U praxis_chess_user -d praxis_sample < games.sql
"""
import json, sys


def q(v):
    if v is None:
        return 'NULL'
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


data = json.load(open(sys.argv[1], encoding='utf-8'))
print('BEGIN;')
for g in data['games']:
    cols = {
        'id': g['id'], 'chess_com_id': g['chess_com_id'], 'source': 'CHESS_COM', 'username': data['player'],
        'played_at': g['played_at'], 'time_control': '600', 'time_class': 'rapid',
        'player_color': g['player_color'], 'result': g['result'],
        'opening_eco': g['eco'], 'opening_name': g['opening_name'], 'raw_pgn': g['pgn'],
        'analysis_status': 'PENDING', 'white_rating': g['white_rating'], 'black_rating': g['black_rating'],
    }
    print(f"INSERT INTO games ({', '.join(cols)}, created_at) VALUES ({', '.join(q(v) for v in cols.values())}, now()) "
          f"ON CONFLICT (chess_com_id) DO NOTHING;")
print('COMMIT;')
