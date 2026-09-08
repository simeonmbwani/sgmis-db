import django.db.models.deletion
from django.db import migrations, models


def ensure_station_column(apps, schema_editor):
    User = apps.get_model("accounts", "User")
    field = User._meta.get_field("station")
    table = User._meta.db_table
    existing = {c.name for c in schema_editor.connection.introspection.get_table_description(schema_editor.connection.cursor(), table)}
    if field.column not in existing:
        schema_editor.add_field(User, field)

    # Add the model index only when it is missing. This makes the repair safe for
    # databases where somebody already restored the column manually.
    existing_indexes = {name for name, info in schema_editor.connection.introspection.get_constraints(schema_editor.connection.cursor(), table).items() if info.get("index")}
    for index in User._meta.indexes:
        if index.name == "accounts_us_station_1cacb2_idx" and index.name not in existing_indexes:
            schema_editor.add_index(User, index)


def reverse_noop(apps, schema_editor):
    # Deliberately do not drop a production column during rollback.
    pass


class Migration(migrations.Migration):
    dependencies = [
        ("accounts", "0002_user_station_user_accounts_us_station_1cacb2_idx"),
        ("stations", "0002_guardpair"),
    ]

    operations = [migrations.RunPython(ensure_station_column, reverse_noop)]
