import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("accounts", "0001_initial"),
        ("stations", "0002_guardpair"),
    ]

    operations = [
        migrations.SeparateDatabaseAndState(
            database_operations=[],
            state_operations=[
                migrations.AddField(
                    model_name="user",
                    name="station",
                    field=models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="assigned_guards",
                        to="stations.station",
                    ),
                ),
                migrations.AddIndex(
                    model_name="user",
                    index=models.Index(
                        fields=["station"],
                        name="accounts_us_station_1cacb2_idx",
                    ),
                ),
            ],
        ),
    ]