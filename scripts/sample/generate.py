"""
Sample games for "Explore with sample games" (POLISH_PLAN.md U7).

Stockfish plays both sides at club strength (UCI "Skill Level"), so the games
hold the mistakes a club player makes: hung pieces, missed forks, slow
openings. The sample player is "sample_sam", a made-up name; the opponents are
made-up too. Every move is the engine's.

What is synthetic, and said so in the app's sample banner and the docs:
  - the clock: each move's thinking time is drawn at random (a rapid 10+0
    game), so "time trouble" here is a dice roll, not a cause;
  - the ratings: Elo moves by the standard formula from each result, starting
    at 1180, with each opponent rated by its skill level;
  - the dates: games spread over August to mid-October 2026.

Writes one JSON file of games, each with a Chess.com-shaped PGN, ready for
load_sql.py. Usage:

    python scripts/sample/generate.py --engine <stockfish.exe> --out games.json --games 16
"""
import argparse, datetime as dt, json, random, subprocess, uuid
import chess

PLAYER = 'sample_sam'
OPPONENTS = ['rook_lift', 'knightshade', 'pawnstorm42', 'quiet_bishop', 'endgame_ella', 'castle_early',
             'gambit_gus', 'tempo_tom', 'zugzwang_zoe', 'fianchetto_fred']
# A line to start from, its ECO code, and which side the sample player takes in it.
OPENINGS = [
    ('e4 e5 Nf3 Nc6 Bc4 Bc5', 'C50', 'white'),
    ('e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3', 'B56', 'black'),
    ('e4 e6 d4 d5 Nc3', 'C00', 'black'),
    ('e4 c6 d4 d5 Nc3 dxe4 Nxe4', 'B15', 'black'),
    ('d4 d5 c4 e6 Nc3 Nf6', 'D30', 'white'),
    ('d4 d5 Bf4 Nf6 e3', 'D02', 'white'),
    ('e4 d5 exd5 Qxd5 Nc3', 'B01', 'black'),
    ('e4 d6 d4 Nf6 Nc3 g6', 'B07', 'black'),
    ('d4 Nf6 c4 g6 Nc3 Bg7 e4 d6', 'E70', 'black'),
    ('e4 e5 Nf3 Nc6 Bb5 a6', 'C60', 'white'),
    ('d4 d5 c4 dxc4 Nf3', 'D20', 'white'),
    ('c4 e5 Nc3 Nf6', 'A20', 'white'),
]
SAM_SKILL = 4
# Names as the ECO table gives them for these codes.
ECO_NAMES = {'C50': 'Italian Game', 'B56': 'Sicilian Defense', 'C00': 'French Defense', 'B15': 'Caro-Kann Defense', 'D30': "Queen's Gambit Declined", 'D02': "Queen's Pawn Game", 'B01': 'Scandinavian Defense', 'B07': 'Pirc Defense', 'E70': "King's Indian Defense", 'C60': 'Ruy Lopez', 'D20': "Queen's Gambit Accepted", 'A20': 'English Opening'}


class Engine:
    def __init__(self, path):
        self.p = subprocess.Popen([path], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        self.send('uci'); self.wait('uciok')

    def send(self, c):
        self.p.stdin.write(c + '\n'); self.p.stdin.flush()

    def wait(self, tok):
        while True:
            line = self.p.stdout.readline()
            if line.startswith(tok):
                return line

    def move(self, board, skill, movetime):
        self.send(f'setoption name Skill Level value {skill}')
        self.send('isready'); self.wait('readyok')
        self.send('position fen ' + board.fen())
        self.send(f'go movetime {movetime}')
        score = None
        while True:
            line = self.p.stdout.readline()
            if ' score cp ' in line:
                score = int(line.split(' score cp ')[1].split()[0]) / 100
            elif ' score mate ' in line:
                score = 100 if int(line.split(' score mate ')[1].split()[0]) > 0 else -100
            if line.startswith('bestmove'):
                return chess.Move.from_uci(line.split()[1]), score

    def quit(self):
        self.send('quit')


def clock(secs):
    secs = max(0, int(secs))
    return f'{secs // 3600}:{secs % 3600 // 60:02d}:{secs % 60:02d}'


def think(rng, ply, left):
    """Seconds spent on one move: quick in the opening, slower in the middlegame, hurried when low."""
    if left < 60:
        return rng.uniform(1, 4)
    if ply < 16:
        return rng.uniform(1, 6)
    t = rng.lognormvariate(2.3, 0.7)          # median about 10 s
    if rng.random() < 0.06:
        t += rng.uniform(40, 120)             # a long think
    return min(t, left - 1)


def play(engine, rng, opening, sam_white, opp_skill):
    board = chess.Board()
    for san in opening.split():
        board.push_san(san)
    clocks = {chess.WHITE: 600.0, chess.BLACK: 600.0}
    comments = []
    for i, _ in enumerate(board.move_stack):
        side = chess.WHITE if i % 2 == 0 else chess.BLACK
        clocks[side] -= rng.uniform(1, 3)
        comments.append(clocks[side])
    losing = {chess.WHITE: 0, chess.BLACK: 0}
    termination = None
    while not board.is_game_over(claim_draw=True) and len(board.move_stack) < 180:
        side = board.turn
        sam = (side == chess.WHITE) == sam_white
        mv, score = engine.move(board, SAM_SKILL if sam else opp_skill, 60)
        # Resign after four moves a rook or more down, as people do.
        losing[side] = losing[side] + 1 if score is not None and score < -5 else 0
        if losing[side] >= 4:
            termination = ('resign', side)
            break
        clocks[side] -= think(rng, len(board.move_stack), clocks[side])
        board.push(mv)
        comments.append(clocks[side])
    if termination:
        winner = not termination[1]
        result = '1-0' if winner == chess.WHITE else '0-1'
        how = 'resignation'
    else:
        outcome = board.outcome(claim_draw=True)
        if outcome is None:
            result, how = '1/2-1/2', 'agreement'
        else:
            result = outcome.result()
            how = {chess.Termination.CHECKMATE: 'checkmate', chess.Termination.STALEMATE: 'stalemate',
                   chess.Termination.INSUFFICIENT_MATERIAL: 'insufficient material',
                   chess.Termination.THREEFOLD_REPETITION: 'repetition',
                   chess.Termination.FIFTY_MOVES: 'the 50-move rule'}.get(outcome.termination, 'agreement')
    return board, comments, result, how


def pgn(board, comments, headers):
    out = [f'[{k} "{v}"]' for k, v in headers.items()]
    replay = chess.Board()
    moves = []
    for i, mv in enumerate(board.move_stack):
        san = replay.san(mv)
        replay.push(mv)
        n = i // 2 + 1
        moves.append(f'{n}. {san}' if i % 2 == 0 else f'{n}... {san}')
        moves[-1] += ' {[%clk ' + clock(comments[i]) + ']}'
    return '\n'.join(out) + '\n\n' + ' '.join(moves) + ' ' + headers['Result'] + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--engine', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--games', type=int, default=16)
    ap.add_argument('--seed', type=int, default=2026)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    engine = Engine(a.engine)
    rating = 1180
    start = dt.datetime(2026, 8, 2, tzinfo=dt.timezone.utc)
    games = []
    for g in range(a.games):
        line, eco, sam_side = OPENINGS[g % len(OPENINGS)]
        sam_white = sam_side == 'white'
        opp_skill = rng.choice([2, 3, 4, 5, 6, 7])
        opp_rating = rating + 70 * (opp_skill - SAM_SKILL) + rng.randint(-40, 40)
        played = start + dt.timedelta(days=g * 4.6 + rng.uniform(0, 2), hours=rng.uniform(2, 16))
        board, comments, result, how = play(engine, rng, line, sam_white, opp_skill)
        score = {'1-0': 1.0, '0-1': 0.0}.get(result, 0.5)
        sam_score = score if sam_white else 1 - score
        opp = rng.choice(OPPONENTS)
        white, black = (PLAYER, opp) if sam_white else (opp, PLAYER)
        we, be = (rating, opp_rating) if sam_white else (opp_rating, rating)
        winner = white if result == '1-0' else black if result == '0-1' else None
        headers = {
            'Event': 'Live Chess', 'Site': 'Chess.com', 'Date': played.strftime('%Y.%m.%d'), 'Round': '-',
            'White': white, 'Black': black, 'Result': result, 'ECO': eco,
            'WhiteElo': we, 'BlackElo': be, 'TimeControl': '600',
            'Termination': f'{winner} won by {how}' if winner else f'Game drawn by {how}',
            'UTCDate': played.strftime('%Y.%m.%d'), 'UTCTime': played.strftime('%H:%M:%S'),
        }
        games.append({
            'id': str(uuid.UUID(int=rng.getrandbits(128), version=4)),
            'chess_com_id': f'sample-{g + 1:03d}',
            'played_at': played.isoformat(),
            'player_color': 'white' if sam_white else 'black',
            'result': 'win' if sam_score == 1 else 'loss' if sam_score == 0 else 'draw',
            'eco': eco, 'opening_name': ECO_NAMES[eco], 'white_rating': we, 'black_rating': be,
            'pgn': pgn(board, comments, headers),
        })
        expected = 1 / (1 + 10 ** ((opp_rating - rating) / 400))
        rating = round(rating + 20 * (sam_score - expected))
        print(f'game {g + 1}/{a.games}: {white} - {black} {result} ({how}), {len(board.move_stack)} plies, rating now {rating}', flush=True)
    engine.quit()
    json.dump({'player': PLAYER, 'games': games}, open(a.out, 'w', encoding='utf-8'), indent=1)


if __name__ == '__main__':
    main()
