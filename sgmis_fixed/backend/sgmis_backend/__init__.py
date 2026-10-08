# SGMIS Backend Package

def _apply_python314_django_context_patch():
    """
    Python 3.14 changed copy semantics for super() proxies, causing
    django.template.context.BaseContext.__copy__ to fail with:
    AttributeError: 'super' object has no attribute 'dicts' and no __dict__ for setting new attributes.
    This patch safely shallow-copies the BaseContext instance so Django template
    rendering works properly on Python 3.14 without affecting Python <= 3.13.
    """
    try:
        from django.template.context import BaseContext
        
        def _safe_base_context_copy(self):
            duplicate = self.__class__.__new__(self.__class__)
            duplicate.__dict__.update(self.__dict__)
            duplicate.dicts = self.dicts[:]
            return duplicate

        BaseContext.__copy__ = _safe_base_context_copy
    except Exception:
        pass

_apply_python314_django_context_patch()
