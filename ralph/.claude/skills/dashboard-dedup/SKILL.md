# Dashboard Dedup UI Skill

## Cél
Duplikátum döntési felület: kártyás nézet, keep/archive gombok, rag_winner frissítés.

## FastAPI végpontok

```python
# GET /api/duplicates – csoportok listája döntésre várva
@app.get("/api/duplicates")
def get_duplicates(status: str = "pending", limit: int = 50, offset: int = 0):
    con = db(DB_PATH)
    groups = con.execute("""
        SELECT g.id, g.match_type, g.item_count, g.total_size_mb, g.saveable_mb, g.status,
               COUNT(CASE WHEN m.decision='pending' THEN 1 END) as pending_count
        FROM dup_groups g
        LEFT JOIN dup_members m ON m.group_id = g.id
        WHERE g.status = ?
        GROUP BY g.id
        ORDER BY g.saveable_mb DESC
        LIMIT ? OFFSET ?
    """, (status, limit, offset)).fetchall()
    return [dict(g) for g in groups]

# GET /api/duplicates/{group_id} – csoport részletei
@app.get("/api/duplicates/{group_id}")
def get_group_detail(group_id: int):
    con = db(DB_PATH)
    members = con.execute("""
        SELECT m.id, m.item_type, m.item_id, m.decision, m.is_winner,
               f.name, f.size, f.mtime, f.path,
               e.subject, e.sender, e.sent_at
        FROM dup_members m
        LEFT JOIN files f ON m.item_type='file' AND m.item_id=f.path
        LEFT JOIN emails e ON m.item_type='email' AND m.item_id=CAST(e.id AS TEXT)
        WHERE m.group_id = ?
    """, (group_id,)).fetchall()
    return [dict(m) for m in members]

# POST /api/duplicates/{group_id}/decision – döntés rögzítése
@app.post("/api/duplicates/{group_id}/decision")
def decide(group_id: int, body: dict):
    # body: { "winner_id": "...", "action": "keep_one" | "keep_all" | "dismiss" }
    con = db(DB_PATH)
    
    if body["action"] == "keep_one":
        winner_id = body["winner_id"]
        # winner → rag_winner=1, többiek → rag_winner=0
        con.execute("UPDATE dup_members SET decision='keep', is_winner=1 WHERE id=?",
                    (winner_id,))
        con.execute("""UPDATE dup_members SET decision='archive', is_winner=0
                       WHERE group_id=? AND id!=?""", (group_id, winner_id))
        # files/emails frissítése
        _apply_winner(con, group_id, winner_id)
    
    elif body["action"] == "keep_all":
        con.execute("UPDATE dup_members SET decision='keep', is_winner=1 WHERE group_id=?",
                    (group_id,))
    
    con.execute("UPDATE dup_groups SET status='reviewed', reviewed_at=? WHERE id=?",
                (time.time(), group_id))
    con.commit()
    return {"ok": True}

def _apply_winner(con, group_id: int, winner_member_id: int):
    members = con.execute(
        "SELECT item_type, item_id, id FROM dup_members WHERE group_id=?",
        (group_id,)
    ).fetchall()
    for m in members:
        rag = 1 if m["id"] == winner_member_id else 0
        if m["item_type"] == "file":
            con.execute("UPDATE files SET rag_winner=? WHERE path=?",
                        (rag, m["item_id"]))
        elif m["item_type"] == "email":
            con.execute("UPDATE emails SET rag_winner=? WHERE id=?",
                        (rag, int(m["item_id"])))
        elif m["item_type"] == "attachment":
            con.execute("UPDATE attachments SET rag_winner=? WHERE id=?",
                        (rag, int(m["item_id"])))
```

## Frontend kártya struktúra (HTML/JS)

```html
<!-- Duplikátum kártya -->
<div class="dup-card" data-group-id="47">
  <div class="dup-header">
    <span class="badge badge-sha256">SHA256</span>
    <span>3 fájl · 248 MB · 165 MB megtakarítható</span>
  </div>
  <div class="dup-members">
    <!-- radio button: melyiket tartja meg -->
    <label class="member winner-candidate">
      <input type="radio" name="winner-47" value="member-id-1" checked>
      <span class="member-name">report.pdf</span>
      <span class="member-path">/archive/2023/</span>
      <span class="member-size">248 MB</span>
    </label>
    <!-- ... többi tag ... -->
  </div>
  <div class="dup-actions">
    <button onclick="decide(47, 'keep_one')">Ezt tartom meg</button>
    <button onclick="decide(47, 'keep_all')">Mindet megtartom</button>
    <button onclick="decide(47, 'dismiss')">Kihagyom</button>
  </div>
</div>
```

## Statisztika a dashboardon

```sql
SELECT
  COUNT(*) FILTER (WHERE status='pending')  as pending,
  COUNT(*) FILTER (WHERE status='reviewed') as reviewed,
  SUM(saveable_mb) FILTER (WHERE status='pending') as pending_mb
FROM dup_groups;
```
